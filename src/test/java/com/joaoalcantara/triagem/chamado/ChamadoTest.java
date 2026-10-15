package com.joaoalcantara.triagem.chamado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChamadoTest {

    private static final Instant AGORA = Instant.parse("2026-10-15T12:00:00Z");
    private static final Classificacao CLASSIFICACAO =
            new Classificacao(Categoria.ACESSO, Prioridade.ALTA, "Usuario bloqueado apos troca de senha");

    @Test
    @DisplayName("registrar a triagem preenche a classificacao e encerra a reserva")
    void registraTriagem() {
        Chamado chamado = new Chamado("texto", AGORA);
        chamado.reservarAte(AGORA.plusSeconds(120));

        chamado.registrarTriagem(CLASSIFICACAO, "gemini", AGORA);

        assertThat(chamado.getStatus()).isEqualTo(StatusChamado.TRIADO);
        assertThat(chamado.getCategoria()).isEqualTo(Categoria.ACESSO);
        assertThat(chamado.getProvedor()).isEqualTo("gemini");
        assertThat(chamado.getReservadoAte()).isNull();
    }

    @Test
    @DisplayName("chamado triado nao pode ser triado de novo")
    void naoTriaDuasVezes() {
        Chamado chamado = new Chamado("texto", AGORA);
        chamado.registrarTriagem(CLASSIFICACAO, "gemini", AGORA);

        assertThatThrownBy(() -> chamado.registrarTriagem(CLASSIFICACAO, "gemini", AGORA))
                .isInstanceOf(TransicaoInvalidaException.class);
    }

    @Test
    @DisplayName("chamado triado nao vai para triagem manual")
    void triadoNaoVaiParaManual() {
        Chamado chamado = new Chamado("texto", AGORA);
        chamado.registrarTriagem(CLASSIFICACAO, "gemini", AGORA);

        assertThatThrownBy(chamado::encaminharParaTriagemManual)
                .isInstanceOf(TransicaoInvalidaException.class);
    }

    @Test
    @DisplayName("pendente de triagem manual ainda pode ser triado")
    void pendenteAindaPodeSerTriado() {
        Chamado chamado = new Chamado("texto", AGORA);
        chamado.encaminharParaTriagemManual();

        chamado.registrarTriagem(CLASSIFICACAO, "gemini", AGORA);

        assertThat(chamado.getStatus()).isEqualTo(StatusChamado.TRIADO);
    }
}
