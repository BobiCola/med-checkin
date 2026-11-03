package com.luge.medreminder

import android.content.Context
import androidx.room.*

@Entity(tableName = "medicine")
data class MedicineEntity(
    @PrimaryKey val id: String, val name: String, val dosage: String, val startDate: String, val endDate: String?,
    val freq: String, val everyN: Int, val weekdays: Int, val sort: Int,
    val winBefore: Int? = null, val winAfter: Int? = null,
)

@Entity(tableName = "app_setting")
data class AppSettingEntity(@PrimaryKey val key: String, val value: Int)

@Entity(tableName = "dose_slot", indices = [Index("medId")])
data class DoseSlotEntity(@PrimaryKey val id: String, val medId: String, val label: String, val hour: Int, val minute: Int, val sort: Int)

@Entity(tableName = "intake_record", indices = [Index("date"), Index("medId")])
data class IntakeRecordEntity(
    @PrimaryKey val id: String, val medId: String, val slotId: String, val date: String, val scheduledMin: Int,
    val label: String, val status: String, val takenAt: Long, val note: String,
)

@Entity(tableName = "daily_plan_snapshot", primaryKeys = ["date", "medId", "slotId"])
data class DailyPlanSnapshotEntity(val date: String, val medId: String, val slotId: String, val label: String, val minutes: Int)

@Entity(tableName = "inventory")
data class InventoryEntity(
    @PrimaryKey val medId: String, val enabled: Boolean, val remaining: Double, val perDose: Double, val unit: String, val lowThreshold: Double,
)

@Dao
interface MedDao {
    @Query("SELECT * FROM medicine ORDER BY sort") fun medicines(): List<MedicineEntity>
    @Query("SELECT * FROM dose_slot ORDER BY sort") fun slots(): List<DoseSlotEntity>
    @Query("SELECT * FROM intake_record") fun records(): List<IntakeRecordEntity>
    @Query("SELECT * FROM daily_plan_snapshot") fun snapshots(): List<DailyPlanSnapshotEntity>
    @Query("SELECT * FROM inventory") fun inventory(): List<InventoryEntity>
    @Query("SELECT * FROM app_setting") fun settings(): List<AppSettingEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertSettings(l: List<AppSettingEntity>)

    @Query("DELETE FROM medicine") fun clearMeds()
    @Query("DELETE FROM dose_slot") fun clearSlots()
    @Query("DELETE FROM intake_record") fun clearRecords()
    @Query("DELETE FROM daily_plan_snapshot") fun clearSnapshots()
    @Query("DELETE FROM inventory") fun clearInventory()
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertMeds(l: List<MedicineEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertSlots(l: List<DoseSlotEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertRecords(l: List<IntakeRecordEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertSnapshots(l: List<DailyPlanSnapshotEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertInventory(l: List<InventoryEntity>)

    @Transaction
    fun replaceAll(d: AppData) {
        clearMeds(); clearSlots(); clearRecords(); clearSnapshots(); clearInventory()
        insertMeds(d.medicines.mapIndexed { i, m -> MedicineEntity(m.id, m.name, m.dosage, m.startDate, m.endDate, m.freq.name, m.everyN, m.weekdays, i, m.winBefore, m.winAfter) })
        insertSettings(listOf(AppSettingEntity("winBefore", d.winBefore), AppSettingEntity("winAfter", d.winAfter)))
        insertSlots(d.medicines.flatMap { m -> m.slots.mapIndexed { i, s -> DoseSlotEntity(s.id, m.id, s.label, s.hour, s.minute, i) } })
        insertRecords(d.records.map { IntakeRecordEntity(it.id, it.medId, it.slotId, it.date, it.scheduledMin, it.label, it.status.name, it.takenAt, it.note) })
        // Empty-day marker row (medId = "") keeps "nothing planned" days frozen too.
        insertSnapshots(d.snapshots.flatMap { (date, items) ->
            items.map { DailyPlanSnapshotEntity(date, it.medId, it.slotId, it.label, it.minutes) } + DailyPlanSnapshotEntity(date, "", "", "", -1)
        })
        insertInventory(d.inventory.values.map { InventoryEntity(it.medId, it.enabled, it.remaining, it.perDose, it.unit, it.lowThreshold) })
    }

    fun loadAll(late: Int): AppData {
        val slots = slots().groupBy { it.medId }
        val st = settings().associate { it.key to it.value }
        return AppData(
            medicines = medicines().map { e ->
                Medicine(e.id, e.name, e.dosage, slots[e.id].orEmpty().map { DoseSlot(it.id, it.label, it.hour, it.minute) }.sortedBy { it.minutes },
                    e.startDate, e.endDate, runCatching { Freq.valueOf(e.freq) }.getOrDefault(Freq.DAILY), e.everyN, e.weekdays, e.winBefore, e.winAfter)
            },
            records = records().map { Record(it.medId, it.slotId, it.date, it.scheduledMin, it.label, Status.valueOf(it.status), it.takenAt, it.note, it.id) },
            lateThresholdMin = late,
            winBefore = st["winBefore"] ?: 30,
            winAfter = st["winAfter"] ?: 60,
            snapshots = snapshots().groupBy { it.date }.mapValues { (_, l) -> l.filter { it.medId.isNotEmpty() }.map { PlanItem(it.medId, it.slotId, it.label, it.minutes) } },
            inventory = inventory().associate { it.medId to Inventory(it.medId, it.enabled, it.remaining, it.perDose, it.unit, it.lowThreshold) },
        )
    }
}

@Database(entities = [MedicineEntity::class, DoseSlotEntity::class, IntakeRecordEntity::class, DailyPlanSnapshotEntity::class, InventoryEntity::class, AppSettingEntity::class],
    version = 2, exportSchema = true)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): MedDao
    companion object {
        fun build(ctx: Context) = Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "med.db")
            .addMigrations(MIGRATION_1_2)
            .allowMainThreadQueries() // tiny dataset; initial load happens synchronously at startup
            .build()
    }
}

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { MIGRATION_1_2_SQL.forEach { db.execSQL(it) } }
}
