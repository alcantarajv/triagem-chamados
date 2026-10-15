package com.joaoalcantara.triagem.chamado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChamadoTest {

    private static final Instant AGORA = Instant.parse("2026-10-15T12:00:00Z");
    private static final Classificacao CLASSIFICACAO =
            new Classificacao(Categoria.ACESSO, Prioridade.ALTA, "Usuario bloqueado apos troca de senha");
    private static final List<Duration> ESPERAS =
            List.of(Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2));

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
    @DisplayName("chamado triado nao registra falha de triagem")
    void triadoNaoRegistraFalha() {
        Chamado chamado = new Chamado("texto", AGORA);
        chamado.registrarTriagem(CLASSIFICACAO, "gemini", AGORA);

        assertThatThrownBy(() -> chamado.registrarFalhaDaTriagem(AGORA, ESPERAS))
                .isInstanceOf(TransicaoInvalidaException.class);
    }

    @Test
    @DisplayName("primeira falha: pendente, com nova tentativa depois da primeira espera")
    void primeiraFalha() {
        Chamado chamado = new Chamado("texto", AGORA);
        chamado.reservarAte(AGORA.plusSeconds(300));

        chamado.registrarFalhaDaTriagem(AGORA, ESPERAS);

        assertThat(chamado.getStatus()).isEqualTo(StatusChamado.PENDENTE_TRIAGEM_MANUAL);
        assertThat(chamado.getFalhasDeTriagem()).isEqualTo(1);
        assertThat(chamado.getProximaTentativaEm()).isEqualTo(AGORA.plus(Duration.ofMinutes(5)));
        assertThat(chamado.getReservadoAte()).isNull();
    }

    @Test
    @DisplayName("a espera cresce a cada falha, e esgotada a lista o chamado espera so uma pessoa")
    void esperaCrescenteAteDesistir() {
        Chamado chamado = new Chamado("texto", AGORA);

        chamado.registrarFalhaDaTriagem(AGORA, ESPERAS);
        assertThat(chamado.getProximaTentativaEm()).isEqualTo(AGORA.plus(Duration.ofMinutes(5)));

        chamado.registrarFalhaDaTriagem(AGORA, ESPERAS);
        assertThat(chamado.getProximaTentativaEm()).isEqualTo(AGORA.plus(Duration.ofMinutes(30)));

        chamado.registrarFalhaDaTriagem(AGORA, ESPERAS);
        assertThat(chamado.getProximaTentativaEm()).isEqualTo(AGORA.plus(Duration.ofHours(2)));

        chamado.registrarFalhaDaTriagem(AGORA, ESPERAS);
        assertThat(chamado.getProximaTentativaEm()).isNull();
        assertThat(chamado.getFalhasDeTriagem()).isEqualTo(4);
        assertThat(chamado.getStatus()).isEqualTo(StatusChamado.PENDENTE_TRIAGEM_MANUAL);
    }

    @Test
    @DisplayName("sem esperas configuradas, a primeira falha ja e definitiva")
    void semEsperas() {
        Chamado chamado = new Chamado("texto", AGORA);

        chamado.registrarFalhaDaTriagem(AGORA, List.of());

        assertThat(chamado.getProximaTentativaEm()).isNull();
    }

    @Test
    @DisplayName("pendente triado na revisao: classificado, sem tentativa agendada")
    void pendenteTriadoNaRevisao() {
        Chamado chamado = new Chamado("texto", AGORA);
        chamado.registrarFalhaDaTriagem(AGORA, ESPERAS);

        chamado.registrarTriagem(CLASSIFICACAO, "groq", AGORA.plus(Duration.ofMinutes(5)));

        assertThat(chamado.getStatus()).isEqualTo(StatusChamado.TRIADO);
        assertThat(chamado.getProximaTentativaEm()).isNull();
    }
}
