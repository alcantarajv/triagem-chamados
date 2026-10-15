package com.joaoalcantara.triagem.infra;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Um relogio que o teste move.
 *
 * <p>A regra "a reserva vence em dois minutos" so e verificavel se der para
 * pular dois minutos. A alternativa — configurar uma reserva de milissegundos e
 * esperar de verdade — daria um teste lento e instavel, que nao prova a regra
 * com os valores de producao.</p>
 *
 * <p>{@code @Primary} em vez de sobrescrever o bean {@code relogio}: o contexto
 * ganha os dois e este vence a injecao, sem habilitar sobrescrita de beans.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class RelogioAjustavel {

    @Bean
    @Primary
    ClockMovel relogioAjustavel() {
        return new ClockMovel(Instant.now());
    }

    /** Permite avancar o tempo sem trocar a instancia injetada nos beans. */
    public static class ClockMovel extends Clock {

        private final AtomicReference<Instant> agora;

        ClockMovel(Instant inicio) {
            this.agora = new AtomicReference<>(inicio);
        }

        public void avancar(Duration quanto) {
            agora.updateAndGet(instante -> instante.plus(quanto));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora.get();
        }
    }
}
