package com.ajay.storyboard.ai

import androidx.compose.runtime.mutableStateMapOf
import com.ajay.storyboard.data.Repository
import com.ajay.storyboard.data.Roundtable
import com.ajay.storyboard.data.RoundtableMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Runs AI Roundtables in the app scope (leaving the screen doesn't stop them):
 *  round 1: every AI answers on its own;
 *  before each rebuttal round: the moderator lists agreement and contradictions and guides each AI
 *  (with [Roundtable.pauseForGuidance] the run waits so you can edit that guidance);
 *  rebuttal rounds: each AI reads the others and the guidance, defends or concedes, and revises;
 *  then the moderator writes the verdict. "Add a round" and follow-up questions continue the same session.
 */
class RoundtableService(
    private val repo: Repository,
    private val ai: AiGateway,
    private val catalog: AiCatalog,
    private val scope: CoroutineScope,
) {
    /** roundtable id -> what is happening right now. */
    val working = mutableStateMapOf<Long, String>()
    private val jobs = mutableMapOf<Long, Job>()

    fun isRunning(id: Long) = jobs[id]?.isActive == true

    suspend fun create(
        bookId: Long?, question: String, context: String, targets: List<String>, moderator: String,
        rounds: Int = 1, pauseForGuidance: Boolean = true,
    ): Long {
        val title = question.trim().lineSequence().first().take(80)
        val id = repo.roundtables.insert(
            Roundtable(bookId = bookId, title = title, question = question.trim(), context = context,
                targets = targets.joinToString(","), moderator = moderator,
                rounds = rounds.coerceIn(0, MAX_ROUNDS), pauseForGuidance = pauseForGuidance)
        )
        repo.roundtables.insertMessage(RoundtableMessage(roundtableId = id, turn = 1, round = 0, kind = QUESTION, content = question.trim(), status = DONE))
        resume(id)
        return id
    }

    /** Continues wherever the latest question stopped (missing / failed answers, guidance, rounds, verdict). */
    fun resume(id: Long) = launch(id) { flow(id) }

    /** One more rebuttal round on the latest answers (with fresh guidance), then a new verdict. */
    fun addRound(id: Long) {
        scope.launch {
            val rt = repo.roundtables.get(id) ?: return@launch
            val turn = repo.roundtables.messages(id).maxOfOrNull { it.turn } ?: 1
            val done = repo.roundtables.messages(id).filter { it.turn == turn && it.kind == CHALLENGE }.maxOfOrNull { it.round }?.minus(1) ?: 0
            repo.roundtables.update(rt.copy(rounds = (maxOf(rt.rounds, done) + 1).coerceAtMost(MAX_ROUNDS)))
            resume(id)
        }
    }

    /** Changes the table: who takes part, the moderator, how many rounds, whether to pause for the guidance. */
    fun updateSetup(id: Long, targets: List<String>, moderator: String, rounds: Int, pause: Boolean) {
        scope.launch {
            val rt = repo.roundtables.get(id) ?: return@launch
            repo.roundtables.update(rt.copy(targets = targets.joinToString(","), moderator = moderator,
                rounds = rounds.coerceIn(0, MAX_ROUNDS), pauseForGuidance = pause))
        }
    }

    /** You read (and maybe edited) the moderator's guidance: run the round with it. */
    fun approveGuidance(messageId: Long, text: String) {
        scope.launch {
            val m = repo.roundtables.message(messageId) ?: return@launch
            repo.roundtables.updateMessage(m.copy(content = text.trim(), status = DONE))
            resume(m.roundtableId)
        }
    }

    /** Stop debating: no more rounds for this question, go straight to the verdict. */
    fun skipToVerdict(messageId: Long) {
        scope.launch {
            val m = repo.roundtables.message(messageId) ?: return@launch
            val rt = repo.roundtables.get(m.roundtableId) ?: return@launch
            repo.roundtables.deleteMessage(m)
            repo.roundtables.update(rt.copy(rounds = (m.round - 2).coerceAtLeast(0)))
            resume(m.roundtableId)
        }
    }

    /** A follow-up question: a new turn that sees the earlier questions and verdicts. */
    fun followUp(id: Long, question: String) = launch(id) {
        val turn = (repo.roundtables.messages(id).maxOfOrNull { it.turn } ?: 0) + 1
        repo.roundtables.insertMessage(RoundtableMessage(roundtableId = id, turn = turn, round = 0, kind = QUESTION, content = question.trim(), status = DONE))
        flow(id)
    }

    /** Runs a failed (or poor) message again, optionally with another AI, then continues. */
    fun retry(messageId: Long, targetId: String? = null) {
        scope.launch {
            val m = repo.roundtables.message(messageId) ?: return@launch
            val t = targetId?.let { catalog.resolve(it) }
            repo.roundtables.updateMessage(
                m.copy(status = PENDING, error = null, target = t?.id ?: m.target, targetLabel = t?.label ?: m.targetLabel)
            )
            if (t != null && (m.kind == VERDICT || m.kind == GUIDE)) repo.roundtables.get(m.roundtableId)?.let { repo.roundtables.update(it.copy(moderator = t.id)) }
            resume(m.roundtableId)
        }
    }

    fun stop(id: Long) {
        jobs.remove(id)?.cancel()
        working.remove(id)
        scope.launch {
            repo.roundtables.get(id)?.let { repo.roundtables.update(it.copy(status = "IDLE")) }
            repo.roundtables.messages(id).filter { it.status == RUNNING || it.status == PENDING }
                .forEach { repo.roundtables.updateMessage(it.copy(status = FAILED, error = "Stopped")) }
        }
    }

    private fun launch(id: Long, block: suspend () -> String) {
        if (isRunning(id)) return
        jobs[id] = scope.launch {
            try {
                repo.roundtables.get(id)?.let { repo.roundtables.update(it.copy(status = "RUNNING", updatedAt = System.currentTimeMillis())) }
                val end = block()
                repo.roundtables.get(id)?.let { repo.roundtables.update(it.copy(status = end, updatedAt = System.currentTimeMillis())) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.roundtables.get(id)?.let { repo.roundtables.update(it.copy(status = "FAILED", updatedAt = System.currentTimeMillis())) }
            } finally {
                working.remove(id)
                jobs.remove(id)
            }
        }
    }

    /** Runs the latest question as far as it can go. Returns the table's status afterwards. */
    private suspend fun flow(id: Long): String {
        var rt = repo.roundtables.get(id) ?: return "IDLE"
        var all = repo.roundtables.messages(id)
        val turn = all.maxOf { it.turn }
        val question = all.last { it.turn == turn && it.kind == QUESTION }.content
        val earlier = all.filter { it.turn < turn && (it.kind == QUESTION || (it.kind == VERDICT && it.status == DONE)) }
            .joinToString("\n\n") { if (it.kind == QUESTION) "Question: ${it.content}" else "Verdict: ${it.content}" }
        val context = listOf(rt.context, earlier.takeIf { it.isNotBlank() }?.let { "EARLIER IN THIS DISCUSSION:\n$it" })
            .filterNotNull().filter { it.isNotBlank() }.joinToString("\n\n")
        val members = rt.targetIds

        fun turnMsgs() = all.filter { it.turn == turn }
        suspend fun reload() { all = repo.roundtables.messages(id) }

        // Round 1: answers (an AI added later answers too)
        val answered = turnMsgs().filter { it.kind == ANSWER }.map { it.target }.toSet()
        members.filter { it !in answered }.forEach { tid ->
            repo.roundtables.insertMessage(RoundtableMessage(roundtableId = id, turn = turn, round = 1, kind = ANSWER,
                target = tid, targetLabel = catalog.label(tid)))
        }
        reload()
        working[id] = "Round 1: answers"
        var rerun = runPending(turnMsgs().filter { it.kind == ANSWER && it.target in members }) {
            Prompts.roundtableAnswer(rt.context, question, earlier)
        }
        reload()
        if (turnMsgs().count { it.kind == ANSWER && it.status == DONE } < 2) error("Not enough answers")

        // Rebuttal rounds 2 .. rounds + 1, each opened by the moderator's guidance
        for (round in 2..(rt.rounds.coerceAtLeast(0) + 1)) {
            rt = repo.roundtables.get(id) ?: return "IDLE"
            if (round > rt.rounds + 1) break
            val before = latestPerTarget(turnMsgs().filter { it.round < round }, members)

            var guide = turnMsgs().lastOrNull { it.kind == GUIDE && it.round == round }
            if (guide == null) {
                repo.roundtables.insertMessage(RoundtableMessage(roundtableId = id, turn = turn, round = round, kind = GUIDE,
                    target = rt.moderator, targetLabel = catalog.label(rt.moderator)))
                reload()
                guide = turnMsgs().last { it.kind == GUIDE && it.round == round }
            }
            if (guide.status == PENDING || guide.status == FAILED) {
                working[id] = "Moderator: guidance for round $round"
                val answers = before.map { (tid, text) -> catalog.label(tid) to text }
                runPending(listOf(guide)) { Prompts.roundtableGuide(context, question, answers, round) }
                reload()
                guide = turnMsgs().last { it.kind == GUIDE && it.round == round }
                if (guide.status == FAILED) error("The moderator could not guide round $round")
                if (rt.pauseForGuidance) {
                    repo.roundtables.updateMessage(guide.copy(status = REVIEW))
                    return "WAITING"
                }
            }
            if (guide.status == REVIEW) return "WAITING"

            val existing = turnMsgs().filter { it.kind == CHALLENGE && it.round == round }.map { it.target }.toSet()
            before.keys.filter { it !in existing }.forEach { tid ->
                repo.roundtables.insertMessage(RoundtableMessage(roundtableId = id, turn = turn, round = round, kind = CHALLENGE,
                    target = tid, targetLabel = catalog.label(tid)))
            }
            reload()
            working[id] = "Round $round: rebuttals"
            rerun += runPending(turnMsgs().filter { it.kind == CHALLENGE && it.round == round && it.target in members }) { m ->
                val others = before.filterKeys { it != m.target }.map { (tid, text) -> catalog.label(tid) to text }
                Prompts.roundtableChallenge(context, question, before[m.target].orEmpty(), others, guide.content, catalog.label(m.target))
            }
            reload()
        }

        // Verdict (a new one after every completed round, or when answers were redone)
        val lastRound = turnMsgs().filter { it.kind == ANSWER || it.kind == CHALLENGE }.maxOfOrNull { it.round } ?: 1
        val verdictRound = lastRound + 1
        val lastVerdict = turnMsgs().lastOrNull { it.kind == VERDICT }
        if (lastVerdict == null || lastVerdict.round < verdictRound || (rerun > 0 && lastVerdict.status == DONE)) {
            repo.roundtables.insertMessage(RoundtableMessage(roundtableId = id, turn = turn, round = verdictRound, kind = VERDICT,
                target = rt.moderator, targetLabel = catalog.label(rt.moderator)))
            reload()
        }
        val finals = latestPerTarget(turnMsgs(), members).map { (tid, text) -> catalog.label(tid) to text }
        working[id] = "Moderator: verdict"
        runPending(turnMsgs().filter { it.kind == VERDICT && it.round == verdictRound }) {
            Prompts.roundtableVerdict(rt.context, question, finals)
        }
        return "DONE"
    }

    /** Each AI's most recent finished text (its latest rebuttal, else its answer). */
    private fun latestPerTarget(msgs: List<RoundtableMessage>, members: List<String>): Map<String, String> =
        msgs.filter { (it.kind == ANSWER || it.kind == CHALLENGE) && it.status == DONE && it.target in members }
            .sortedBy { it.round }
            .associate { it.target to it.content }

    /** Asks every PENDING / FAILED message's AI in parallel (each AI has its own page or key). */
    private suspend fun runPending(msgs: List<RoundtableMessage>, prompt: (RoundtableMessage) -> String): Int = coroutineScope {
        val todo = msgs.filter { it.status == PENDING || it.status == FAILED && it.error == null }
        todo.map { m ->
                async {
                    val p = prompt(m)
                    repo.roundtables.updateMessage(m.copy(status = RUNNING, prompt = p, error = null))
                    try {
                        val target = catalog.resolve(m.target) ?: throw AiException("NO_TARGET", "This AI no longer exists")
                        val reply = ai.ask(target, p, "Roundtable")
                        repo.roundtables.updateMessage(m.copy(status = DONE, prompt = p, content = reply.text.trim(), error = null))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        repo.roundtables.updateMessage(m.copy(status = FAILED, prompt = p, error = e.message ?: e.javaClass.simpleName))
                    }
                }
            }.awaitAll()
        todo.size
    }

    companion object {
        const val QUESTION = "QUESTION"
        const val ANSWER = "ANSWER"
        const val GUIDE = "GUIDE"
        const val CHALLENGE = "CHALLENGE"
        const val VERDICT = "VERDICT"
        const val PENDING = "PENDING"
        const val RUNNING = "RUNNING"
        const val REVIEW = "REVIEW"
        const val DONE = "DONE"
        const val FAILED = "FAILED"
        const val MAX_ROUNDS = 8
    }
}
