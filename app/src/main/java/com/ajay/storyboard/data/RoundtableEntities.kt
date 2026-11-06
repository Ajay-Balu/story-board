package com.ajay.storyboard.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One AI Roundtable: a question put to two or more AIs. Round 1 = independent answers; before each later
 * round the moderator lists agreements and contradictions and guides each AI (you can edit that guidance);
 * then each AI reads the others, defends or concedes and revises; finally the moderator writes the verdict.
 * [bookId] is set for plot decisions inside a book (no foreign key: sessions survive the book).
 */
@Entity(tableName = "roundtables", indices = [Index("bookId")])
data class Roundtable(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long?,
    val title: String,
    val question: String,
    /** Story context sent with the question (bible + recent chapters), empty for general questions. */
    val context: String = "",
    /** Target ids, comma separated ("web:CHATGPT,api:2"). */
    val targets: String,
    val moderator: String,
    /** IDLE, RUNNING, WAITING (the moderator's guidance waits for you), DONE, FAILED */
    val status: String = "IDLE",
    /** Rebuttal rounds after the first answers (each starts with the moderator's guidance). */
    @ColumnInfo(defaultValue = "1") val rounds: Int = 1,
    /** Stop before each rebuttal round so you can read and edit the moderator's guidance. */
    @ColumnInfo(defaultValue = "1") val pauseForGuidance: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val targetIds: List<String> get() = targets.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/** One message in a roundtable. kind = QUESTION, ANSWER, GUIDE (moderator, before round [round]), CHALLENGE, VERDICT;
 *  [round] counts from 1 per question. status REVIEW = guidance waiting for you to edit / approve. */
@Entity(
    tableName = "roundtable_messages",
    foreignKeys = [ForeignKey(
        entity = Roundtable::class, parentColumns = ["id"], childColumns = ["roundtableId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("roundtableId")],
)
data class RoundtableMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roundtableId: Long,
    /** Which question of the session (1 = first question, 2 = first follow-up …). */
    val turn: Int,
    val round: Int,
    val kind: String,
    /** The question (for kind = QUESTION) or the AI's text. */
    val content: String = "",
    val target: String = "",
    val targetLabel: String = "",
    val prompt: String = "",
    /** PENDING, RUNNING, REVIEW, DONE, FAILED */
    val status: String = "PENDING",
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
