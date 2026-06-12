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

    /** Called when the parent answers the 5-minute feedback prompt. */
    @Query("UPDATE cry_history SET userFeedback = :feedback WHERE id = :id")
    void updateFeedback(int id, String feedback);

    /** Called after the audio file is saved to disk. */
    @Query("UPDATE cry_history SET audioPath = :path WHERE id = :id")
    void updateAudioPath(int id, String path);
}