package de.uhd.ifi.babycryclassifier;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {CryRecord.class}, version = 4, exportSchema = false)
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

    /** Adds the audioPath column for saved cry recordings. */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                    "ALTER TABLE cry_history ADD COLUMN audioPath TEXT");
        }
    };

    /** Adds the participantId column for user study tagging. */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                    "ALTER TABLE cry_history ADD COLUMN participantId TEXT");
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
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}