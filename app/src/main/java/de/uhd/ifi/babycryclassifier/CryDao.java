package de.uhd.ifi.babycryclassifier;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface CryDao {

    @Insert
    void insert(CryRecord record);

    /** All cries, newest first — observed as LiveData so UI updates automatically. */
    @Query("SELECT * FROM cry_history ORDER BY timestamp DESC")
    LiveData<List<CryRecord>> getAllCries();

    /** Last 50 cries for the history screen. */
    @Query("SELECT * FROM cry_history ORDER BY timestamp DESC LIMIT 50")
    LiveData<List<CryRecord>> getRecentCries();

    @Query("DELETE FROM cry_history")
    void deleteAll();

    @Query("SELECT COUNT(*) FROM cry_history")
    int getCount();
}
