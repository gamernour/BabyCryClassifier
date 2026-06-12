package de.uhd.ifi.babycryclassifier;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Room database entity — one row per detected cry.
 *
 * userFeedback: null = not yet answered, "yes" = correct, "no" = wrong, "unsure" = not sure
 */
@Entity(tableName = "cry_history")
public class CryRecord {

    @PrimaryKey(autoGenerate = true)
    public int id;

    public long timestamp;      // System.currentTimeMillis()
    public String top1Label;
    public int    top1Percent;
    public String top2Label;
    public int    top2Percent;

    // Populated 5 minutes after detection via the feedback prompt
    public String userFeedback;   // null | "yes" | "no" | "unsure"

    // Path to the saved WAV file for this cry recording (internal storage)
    public String audioPath;      // null if not yet saved or saving failed

    // Participant ID for user study (e.g. "P01", "P02")
    public String participantId;  // set from SharedPreferences at record time

    public CryRecord(long timestamp,
                     String top1Label, int top1Percent,
                     String top2Label, int top2Percent,
                     String participantId) {
        this.timestamp     = timestamp;
        this.top1Label     = top1Label;
        this.top1Percent   = top1Percent;
        this.top2Label     = top2Label;
        this.top2Percent   = top2Percent;
        this.userFeedback  = null;
        this.audioPath     = null;
        this.participantId = participantId;
    }
}