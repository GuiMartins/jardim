package com.jardim.avisos

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jardim.motor.agenda.DiaDoTempo
import com.jardim.motor.agenda.ModoAviso
import com.jardim.motor.tempo.Cidade
import com.jardim.motor.tempo.OpenMeteo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalTime

private val Context.dataStore by preferencesDataStore("preferencias")

/**
 * O que o usuário escolhe (horário de cada aviso, cidade) e a última previsão
 * baixada, que fica guardada para o app funcionar sem rede.
 */
class Preferencias(private val context: Context) {

    val horarios: Flow<Map<ModoAviso, LocalTime>> = context.dataStore.data.map { prefs ->
        ModoAviso.entries.associateWith { modo -> prefs[chave(modo)]?.let(LocalTime::parse) ?: padrao(modo) }
    }

    suspend fun horario(modo: ModoAviso): LocalTime = horarios.first().getValue(modo)

    suspend fun mudar(modo: ModoAviso, horario: LocalTime) {
        context.dataStore.edit { it[chave(modo)] = horario.toString() }
    }

    val cidade: Flow<Cidade?> = context.dataStore.data
        .map { prefs -> prefs[CIDADE]?.let { Json.decodeFromString(Cidade.serializer(), it) } }
        .distinctUntilChanged()

    /** A previsão da cidade anterior não vale para a nova: some junto. */
    suspend fun mudarCidade(cidade: Cidade) {
        context.dataStore.edit {
            it[CIDADE] = Json.encodeToString(Cidade.serializer(), cidade)
            it.remove(PREVISAO)
            it.remove(PREVISAO_EM)
        }
    }

    /** A tela de boas-vindas aparece uma vez: na primeira abertura, ou na primeira depois desta versão. */
    val boasVindasVista: Flow<Boolean> = context.dataStore.data.map { it[BOAS_VINDAS] ?: false }.distinctUntilChanged()

    suspend fun marcarBoasVindas() {
        context.dataStore.edit { it[BOAS_VINDAS] = true }
    }

    /** Resposta da Open-Meteo guardada como veio; null antes da primeira. */
    val previsao: Flow<List<DiaDoTempo>?> = context.dataStore.data
        .map { it[PREVISAO] }
        .distinctUntilChanged()
        .map { texto -> texto?.let(OpenMeteo::lerPrevisao) }

    suspend fun previsaoBaixadaEm(): Instant? = context.dataStore.data.first()[PREVISAO_EM]?.let(Instant::parse)

    suspend fun guardarPrevisao(resposta: String, em: Instant) {
        context.dataStore.edit {
            it[PREVISAO] = resposta
            it[PREVISAO_EM] = em.toString()
        }
    }

    private fun chave(modo: ModoAviso) = stringPreferencesKey("horario_${modo.name.lowercase()}")

    private fun padrao(modo: ModoAviso): LocalTime = when (modo) {
        ModoAviso.RESUMO -> LocalTime.of(8, 0)
        ModoAviso.NOITE -> LocalTime.of(19, 0)
    }

    private companion object {
        val CIDADE = stringPreferencesKey("cidade")
        val BOAS_VINDAS = booleanPreferencesKey("boas_vindas_vista")
        val PREVISAO = stringPreferencesKey("previsao")
        val PREVISAO_EM = stringPreferencesKey("previsao_em")
    }
}
