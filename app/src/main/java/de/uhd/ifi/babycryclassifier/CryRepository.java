package de.uhd.ifi.babycryclassifier;

import android.content.Context;

import androidx.lifecycle.LiveData;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Single access point for all database operations.
 * All writes run on a background thread automatically.
 */
public class CryRepository {

    private static CryRepository INSTANCE;

    private final CryDao dao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public CryRepository(Context context) {
        dao = CryDatabase.getInstance(context).cryDao();
    }

    public static CryRepository getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (CryRepository.class) {
                if (INSTANCE == null) {
                    INSTANCE = new CryRepository(context.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Inserts a CryRecord and returns a Future<Long> holding the new row id.
     * Used by CryDetectionService so it can pass the id to FlashActivity,
     * which later passes it to FeedbackActivity for the 5-minute prompt.
     */
    public Future<Long> insertForId(CryRecord record) {
        return executor.submit(() -> dao.insert(record));
    }
    public void insert(CryRecord record) {
        executor.execute(() -> dao.insert(record));
    }

    /** Called when the parent taps Yes / No / Not sure in FeedbackActivity. */
    public void updateFeedback(int id, String feedback) {
        executor.execute(() -> dao.updateFeedback(id, feedback));
    }
    public LiveData<List<CryRecord>> getRecentCries() {
        return dao.getRecentCries();
    }

    public LiveData<List<CryRecord>> getAllCries() {
        return dao.getAllCries();
    }

    public void deleteAll() {
        executor.execute(dao::deleteAll);
    }
}
