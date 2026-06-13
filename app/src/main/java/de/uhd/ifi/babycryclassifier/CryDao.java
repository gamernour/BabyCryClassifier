package de.uhd.ifi.babycryclassifier;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface CryDao {

    @Insert
    long insert(CryRecord record);

    @Query("SELECT * FROM cry_history ORDER BY timestamp DESC")
    LiveData<List<CryRecord>> getAllCries();

    @Query("SELECT * FROM cry_history ORDER BY timestamp DESC LIMIT 50")
    LiveData<List<CryRecord>> getRecentCries();

    @Query("DELETE FROM cry_history")
    void deleteAll();

    @Query("SELECT COUNT(*) FROM cry_history")
    int getCount();

    /** Called when the parent answers the feedback prompt. */
    @Query("UPDATE cry_history SET userFeedback = :feedback WHERE id = :id")
    void updateFeedback(int id, String feedback);

    /** Called after the audio file is saved to disk. */
    @Query("UPDATE cry_history SET audioPath = :path WHERE id = :id")
    void updateAudioPath(int id, String path);

    /** Called when parent selects which Dunstan class the cry actually was. */
    @Query("UPDATE cry_history SET userLabel = :label, labelConfidence = :confidence WHERE id = :id")
    void updateUserLabel(int id, String label, String confidence);

    /** Delete a single record by id. */
    @Query("DELETE FROM cry_history WHERE id = :id")
    void deleteById(int id);

    /** Fetch a single record by id — used to get audioPath before deleting. */
    @Query("SELECT * FROM cry_history WHERE id = :id LIMIT 1")
    CryRecord getById(int id);
}