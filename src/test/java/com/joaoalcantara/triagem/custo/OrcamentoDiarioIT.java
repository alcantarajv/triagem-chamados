package com.joaoalcantara.triagem.custo;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.joaoalcantara.triagem.chamado.Chamado;
import com.joaoalcantara.triagem.chamado.ChamadoRepositorio;
import com.joaoalcantara.triagem.chamado.ChamadoServico;
import com.joaoalcantara.triagem.infra.AmbienteDeTeste;
import com.joaoalcantara.triagem.infra.ProvedorFalso;
import com.joaoalcantara.triagem.infra.RelogioAjustavel.ClockMovel;
import com.joaoalcantara.triagem.infra.TesteDeIntegracao;
import com.joaoalcantara.triagem.provedor.PropriedadesDosProvedores;
import com.joaoalcantara.triagem.triagem.TriadorDeChamados;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Orcamento diario por provedor: esgotado o do Gemini, ele e pulado sem
 * requisicao ate o dia virar — no fuso do Pacifico, que e onde o Google vira a
 * cota.
 *
 * <p>Gastar 450 requisicoes de verdade so para esgotar o orcamento seria lento e
 * nao provaria nada a mais: o teste grava direto no contador o uso de quase o
 * dia inteiro.</p>
 */
@TesteDeIntegracao
class OrcamentoDiarioIT {

    private static final ZoneId PACIFICO = ZoneId.of("America/Los_Angeles");

    @Autowired
    private AmbienteDeTeste ambiente;

    @Autowired
    private TriadorDeChamados triador;

    @Autowired
    private ChamadoServico servico;

    @Autowired
    private ChamadoRepositorio repositorio;

    @Autowired
    private WireMockServer gemini;

    @Autowired
    private WireMockServer groq;

    @Autowired
    private JdbcClient banco;

    @Autowired
    private OrcamentoDiario orcamento;

    @Autowired
    private PropriedadesDosProvedores provedores;

    @Autowired
    private CircuitBreakerRegistry disjuntores;

    @Autowired
    private ClockMovel relogio;

    private int triagens;

    @BeforeEach
    void limpar() {
        ambiente.limpar();
        gemini.stubFor(ProvedorFalso.chat().willReturn(ProvedorFalso.classifica("DUVIDA", "BAIXA", "Duvida de uso")));
        groq.stubFor(ProvedorFalso.chat().willReturn(ProvedorFalso.classifica("DUVIDA", "BAIXA", "Duvida de uso")));
    }

    /** Texto diferente a cada triagem, para o cache nao responder no lugar dos provedores. */
    private Chamado triar() {
        UUID id = servico.registrar("Como exporto o relatorio %d em PDF?".formatted(++triagens)).getId();
        triador.processarProximo();
        return repositorio.findById(id).orElseThrow();
    }

    private int requisicoes(WireMockServer provedor) {
        return provedor.findAll(postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO))).size();
    }

    private void jaUsadoHojeNoGemini(int requisicoes) {
        banco.sql("INSERT INTO uso_diario_dos_provedores (provedor, dia, requisicoes) VALUES ('gemini', :dia, :usadas)")
                .param("dia", LocalDate.ofInstant(relogio.instant(), PACIFICO))
                .param("usadas", requisicoes)
                .update();
    }

    private int orcamentoDoGemini() {
        return provedores.gemini().orcamentoDiario();
    }

    @Test
    @DisplayName("cada requisicao ao provedor conta no orcamento do dia")
    void requisicaoConta() {
        triar();

        assertThat(orcamento.usadoHoje("gemini", PACIFICO)).isEqualTo(1);
        assertThat(orcamento.usadoHoje("groq", ZoneId.of("UTC"))).isZero();
    }

    @Test
    @DisplayName("ultima requisicao do orcamento ainda sai; depois dela, o Gemini e pulado sem requisicao e o Groq responde")
    void orcamentoEsgotado() {
        jaUsadoHojeNoGemini(orcamentoDoGemini() - 1);

        assertThat(triar().getProvedor()).isEqualTo("gemini");
        assertThat(requisicoes(gemini)).isEqualTo(1);

        for (int i = 0; i < 3; i++) {
            assertThat(triar().getProvedor()).isEqualTo("groq");
        }
        assertThat(requisicoes(gemini)).isEqualTo(1);
        assertThat(orcamento.usadoHoje("gemini", PACIFICO)).isEqualTo(orcamentoDoGemini());
    }

    @Test
    @DisplayName("o dia do orcamento do Gemini vira a meia-noite do Pacifico, e o Gemini volta")
    void viradaDoDiaNoPacifico() {
        jaUsadoHojeNoGemini(orcamentoDoGemini());
        assertThat(triar().getProvedor()).isEqualTo("groq");

        Instant meiaNoiteNoPacifico = LocalDate.ofInstant(relogio.instant(), PACIFICO).plusDays(1)
                .atStartOfDay(PACIFICO).toInstant();
        relogio.avancar(Duration.between(relogio.instant(), meiaNoiteNoPacifico).plusSeconds(1));

        assertThat(triar().getProvedor()).isEqualTo("gemini");
    }

    @Test
    @DisplayName("orcamento esgotado nao mexe no circuito: nao conta como falha nem como sucesso do provedor")
    void orcamentoNaoMexeNoCircuito() {
        jaUsadoHojeNoGemini(orcamentoDoGemini());

        for (int i = 0; i < 6; i++) {
            triar();
        }

        CircuitBreaker circuito = disjuntores.circuitBreaker("gemini");
        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuito.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuito.getMetrics().getNumberOfSuccessfulCalls()).isZero();
    }

    @Test
    @DisplayName("varias instancias disputando o fim do orcamento: passam exatamente as que cabem")
    void disputaPeloFimDoOrcamento() throws Exception {
        jaUsadoHojeNoGemini(orcamentoDoGemini() - 5);

        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService instancias = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> reservas = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            reservas.add(instancias.submit(() -> {
                largada.await();
                return orcamento.reservar("gemini", orcamentoDoGemini(), PACIFICO);
            }));
        }
        largada.countDown();

        int aceitas = 0;
        for (Future<Boolean> reserva : reservas) {
            if (reserva.get()) {
                aceitas++;
            }
        }
        instancias.shutdown();

        assertThat(aceitas).isEqualTo(5);
        assertThat(orcamento.usadoHoje("gemini", PACIFICO)).isEqualTo(orcamentoDoGemini());
    }
}
