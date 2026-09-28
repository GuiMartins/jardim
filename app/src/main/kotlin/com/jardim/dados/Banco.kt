package com.jardim.dados

import android.content.Context
import android.content.res.AssetManager
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jardim.motor.agenda.Local
import com.jardim.motor.agenda.StatusAcao
import com.jardim.motor.leitura.Migracoes
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime

// O banco guarda só o que aconteceu: plantios e o histórico de ações. A agenda
// (o que vence quando) é sempre calculada pelo Cronograma do motor.

@Entity(tableName = "plantio")
data class PlantioEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Arquivo do catálogo de onde veio (girassol.json). */
    val especie: String,
    val apelido: String,
    /** O dia 1. "Ressemeei" move esta data para hoje. */
    val dataPlantio: LocalDate,
    /** Cópia do JSON da espécie no dia do plantio: mexer no catálogo depois não muda o plantio. */
    val cultivoJson: String,
    val encerradoEm: LocalDate? = null,
    /** Onde o vaso fica; null nos plantios de antes da pergunta (sem avisos do tempo até responder). */
    val local: Local? = null,
)

@Entity(
    tableName = "registro_acao",
    foreignKeys = [
        ForeignKey(
            entity = PlantioEntity::class,
            parentColumns = ["id"],
            childColumns = ["plantioId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("plantioId")],
)
data class RegistroAcaoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val plantioId: Long,
    val chave: String,
    val venceEm: LocalDate,
    val realizadoEm: LocalDateTime,
    // Room guarda o nome do enum (FEITA, NAO_PRECISOU): renomear exige migração.
    val status: StatusAcao,
)

@Dao
interface JardimDao {
    @Query("SELECT * FROM plantio WHERE encerradoEm IS NULL ORDER BY dataPlantio, id")
    fun plantiosAtivos(): Flow<List<PlantioEntity>>

    @Query(
        "SELECT r.* FROM registro_acao r JOIN plantio p ON p.id = r.plantioId WHERE p.encerradoEm IS NULL",
    )
    fun registrosDosAtivos(): Flow<List<RegistroAcaoEntity>>

    @Insert
    suspend fun inserirPlantio(plantio: PlantioEntity): Long

    @Insert
    suspend fun inserirRegistro(registro: RegistroAcaoEntity): Long

    @Query("DELETE FROM registro_acao WHERE id = :id")
    suspend fun apagarRegistro(id: Long)

    @Query("UPDATE plantio SET dataPlantio = :data WHERE id = :id")
    suspend fun mudarDataPlantio(id: Long, data: LocalDate)

    @Query("UPDATE plantio SET encerradoEm = :data WHERE id = :id")
    suspend fun encerrar(id: Long, data: LocalDate)

    @Query("UPDATE plantio SET local = :local WHERE id = :id")
    suspend fun mudarLocal(id: Long, local: Local)
}

/** Datas em texto ISO (2026-10-01): legível no banco e ordena certo no ORDER BY. */
class Conversores {
    @TypeConverter
    fun deData(valor: String?): LocalDate? = valor?.let(LocalDate::parse)

    @TypeConverter
    fun paraData(data: LocalDate?): String? = data?.toString()

    @TypeConverter
    fun deDataHora(valor: String?): LocalDateTime? = valor?.let(LocalDateTime::parse)

    @TypeConverter
    fun paraDataHora(dataHora: LocalDateTime?): String? = dataHora?.toString()
}

@Database(entities = [PlantioEntity::class, RegistroAcaoEntity::class], version = 2, exportSchema = true)
@TypeConverters(Conversores::class)
abstract class JardimDatabase : RoomDatabase() {
    abstract fun dao(): JardimDao

    companion object {
        fun criar(context: Context): JardimDatabase =
            Room.databaseBuilder(context, JardimDatabase::class.java, "jardim.db")
                .addMigrations(migracao1para2(context.assets))
                .build()
    }
}

/**
 * Versão 2 (2026-09-27): a coluna `local` e os limites de temperatura nas cópias
 * do JSON. Sem eles, o LeitorCultivo recusaria as cópias antigas e as plantas
 * sumiriam da tela. Os limites vêm do JSON atual da espécie no catálogo.
 */
private fun migracao1para2(assets: AssetManager) = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE plantio ADD COLUMN local TEXT")
        db.query("SELECT id, especie, cultivoJson FROM plantio").use { cursor ->
            while (cursor.moveToNext()) {
                val doCatalogo = runCatching {
                    assets.open("catalogo/${cursor.getString(1)}").bufferedReader().use { it.readText() }
                }.getOrNull()
                val completa = Migracoes.comTemperaturas(cursor.getString(2), doCatalogo)
                db.execSQL("UPDATE plantio SET cultivoJson = ? WHERE id = ?", arrayOf<Any>(completa, cursor.getLong(0)))
            }
        }
    }
}
