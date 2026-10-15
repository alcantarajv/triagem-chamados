package com.joaoalcantara.triagem.triagem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.joaoalcantara.triagem.provedor.PropriedadesDaRepeticao;
import com.joaoalcantara.triagem.provedor.PropriedadesDoProvedor;
import com.joaoalcantara.triagem.provedor.PropriedadesDosProvedores;

class ConferenciaDaReservaTest {

    private static final PropriedadesDosProvedores PROVEDORES = new PropriedadesDosProvedores(
            new PropriedadesDoProvedor("http://provedor", "chave", "modelo", Duration.ofSeconds(15)));
    private static final PropriedadesDaRepeticao REPETICAO =
            new PropriedadesDaRepeticao(3, Duration.ofSeconds(1), Duration.ofSeconds(10));

    private static PropriedadesDoWorker workerComReserva(Duration reserva) {
        return new PropriedadesDoWorker(true, Duration.ofSeconds(2), reserva, 10);
    }

    @Test
    @DisplayName("pior caso com os valores de producao: 2 x (3 x 15s + 2 x 10s) = 130s")
    void piorCaso() {
        assertThat(ConferenciaDaReserva.piorCaso(PROVEDORES.gemini(), REPETICAO)).isEqualTo(Duration.ofSeconds(130));
    }

    @Test
    @DisplayName("a reserva padrao de 3 minutos cobre o pior caso")
    void reservaPadraoCobre() {
        assertThatCode(() -> new ConferenciaDaReserva(workerComReserva(Duration.ofMinutes(3)), PROVEDORES, REPETICAO))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("reserva menor que o pior caso: a aplicacao recusa subir, explicando a conta")
    void reservaCurtaDemais() {
        assertThatThrownBy(() -> new ConferenciaDaReserva(workerComReserva(Duration.ofMinutes(2)), PROVEDORES, REPETICAO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("triagem.worker.reserva (120s)")
                .hasMessageContaining("(130s)");
    }
}
