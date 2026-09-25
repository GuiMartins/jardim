package com.jardim.avisos

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jardim.motor.agenda.ModoAviso
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime

private val Context.dataStore by preferencesDataStore("preferencias")

/** Horário de cada aviso, escolhido na tela de configurações. */
class Preferencias(private val context: Context) {

    val horarios: Flow<Map<ModoAviso, LocalTime>> = context.dataStore.data.map { prefs ->
        ModoAviso.entries.associateWith { modo -> prefs[chave(modo)]?.let(LocalTime::parse) ?: padrao(modo) }
    }

    suspend fun horario(modo: ModoAviso): LocalTime = horarios.first().getValue(modo)

    suspend fun mudar(modo: ModoAviso, horario: LocalTime) {
        context.dataStore.edit { it[chave(modo)] = horario.toString() }
    }

    private fun chave(modo: ModoAviso) = stringPreferencesKey("horario_${modo.name.lowercase()}")

    private fun padrao(modo: ModoAviso): LocalTime = when (modo) {
        ModoAviso.RESUMO -> LocalTime.of(8, 0)
        ModoAviso.NOITE -> LocalTime.of(19, 0)
    }
}
