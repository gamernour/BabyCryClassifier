package de.uhd.ifi.babycryclassifier;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {CryRecord.class}, version = 2, exportSchema = false)
public abstract class CryDatabase extends RoomDatabase {

    public abstract CryDao cryDao();

    private static volatile CryDatabase INSTANCE;

    /** Adds the userFeedback column to existing installs without wiping data. */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                    "ALTER TABLE cry_history ADD COLUMN userFeedback TEXT");
        }
    };

    public static CryDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (CryDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    CryDatabase.class,
                                    "cry_history_db")
                            .addMigrations(MIGRATION_1_2)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
