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

    private static final PropriedadesDoProvedor PROVEDOR =
            new PropriedadesDoProvedor("http://provedor", "chave", "modelo", Duration.ofSeconds(15), null);
    private static final PropriedadesDosProvedores PROVEDORES = new PropriedadesDosProvedores(PROVEDOR, PROVEDOR);
    private static final PropriedadesDaRepeticao REPETICAO =
            new PropriedadesDaRepeticao(3, Duration.ofSeconds(1), Duration.ofSeconds(10));

    private static PropriedadesDoWorker workerComReserva(Duration reserva) {
        return new PropriedadesDoWorker(true, Duration.ofSeconds(2), reserva, 10);
    }

    @Test
    @DisplayName("pior caso de um provedor com os valores de producao: 2 x (3 x 15s + 2 x 10s) = 130s")
    void piorCasoDeUmProvedor() {
        assertThat(ConferenciaDaReserva.piorCaso(PROVEDOR, REPETICAO)).isEqualTo(Duration.ofSeconds(130));
    }

    @Test
    @DisplayName("pior caso da cadeia soma os provedores: 260s com dois")
    void piorCasoDaCadeia() {
        assertThat(ConferenciaDaReserva.piorCaso(PROVEDORES, REPETICAO)).isEqualTo(Duration.ofSeconds(260));
    }

    @Test
    @DisplayName("a reserva padrao de 5 minutos cobre a cadeia inteira")
    void reservaPadraoCobre() {
        assertThatCode(() -> new ConferenciaDaReserva(workerComReserva(Duration.ofMinutes(5)), PROVEDORES, REPETICAO))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("reserva que cobria um provedor so nao cobre dois: a aplicacao recusa subir, explicando a conta")
    void reservaCurtaDemais() {
        assertThatThrownBy(() -> new ConferenciaDaReserva(workerComReserva(Duration.ofMinutes(3)), PROVEDORES, REPETICAO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("triagem.worker.reserva (180s)")
                .hasMessageContaining("(260s)");
    }
}
