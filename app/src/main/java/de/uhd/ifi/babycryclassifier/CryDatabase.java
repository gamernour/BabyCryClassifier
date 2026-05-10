package de.uhd.ifi.babycryclassifier;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {CryRecord.class}, version = 1, exportSchema = false)
public abstract class CryDatabase extends RoomDatabase {

    public abstract CryDao cryDao();

    private static volatile CryDatabase INSTANCE;

    public static CryDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (CryDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    CryDatabase.class,
                                    "cry_history_db")
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
