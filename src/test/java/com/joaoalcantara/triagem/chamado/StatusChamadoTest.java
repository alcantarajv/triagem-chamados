package com.joaoalcantara.triagem.chamado;

import static com.joaoalcantara.triagem.chamado.StatusChamado.PENDENTE_TRIAGEM_MANUAL;
import static com.joaoalcantara.triagem.chamado.StatusChamado.RECEBIDO;
import static com.joaoalcantara.triagem.chamado.StatusChamado.TRIADO;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StatusChamadoTest {

    @Test
    @DisplayName("recebido pode ser triado ou ir para triagem manual")
    void recebido() {
        assertThat(RECEBIDO.proximos()).containsExactlyInAnyOrder(TRIADO, PENDENTE_TRIAGEM_MANUAL);
    }

    @Test
    @DisplayName("pendente de triagem manual ainda pode ser triado, mas nao volta a recebido")
    void pendenteAindaPodeSerTriado() {
        assertThat(PENDENTE_TRIAGEM_MANUAL.permiteIrPara(TRIADO)).isTrue();
        assertThat(PENDENTE_TRIAGEM_MANUAL.permiteIrPara(RECEBIDO)).isFalse();
        assertThat(PENDENTE_TRIAGEM_MANUAL.ehFinal()).isFalse();
    }

    @Test
    @DisplayName("triado e final")
    void triadoEFinal() {
        assertThat(TRIADO.ehFinal()).isTrue();
        assertThat(TRIADO.permiteIrPara(PENDENTE_TRIAGEM_MANUAL)).isFalse();
    }

    @Test
    @DisplayName("todo estado chega a triado: nenhum chamado fica preso")
    void todoEstadoChegaATriado() {
        assertThat(Arrays.stream(StatusChamado.values()))
                .allMatch(status -> status == TRIADO || chegaATriado(status));
    }

    private boolean chegaATriado(StatusChamado status) {
        return status.proximos().contains(TRIADO)
                || status.proximos().stream().anyMatch(this::chegaATriado);
    }
}
