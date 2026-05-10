package de.uhd.ifi.babycryclassifier;

import android.content.Context;

import androidx.lifecycle.LiveData;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single access point for all database operations.
 * All writes run on a background thread automatically.
 */
public class CryRepository {

    private static CryRepository INSTANCE;

    private final CryDao dao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private CryRepository(Context context) {
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

    public void insert(CryRecord record) {
        executor.execute(() -> dao.insert(record));
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
