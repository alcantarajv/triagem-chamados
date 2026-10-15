package com.joaoalcantara.triagem.provedor;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.joaoalcantara.triagem.chamado.Chamado;
import com.joaoalcantara.triagem.chamado.ChamadoRepositorio;
import com.joaoalcantara.triagem.chamado.ChamadoServico;
import com.joaoalcantara.triagem.infra.AmbienteDeTeste;
import com.joaoalcantara.triagem.infra.ProvedorFalso;
import com.joaoalcantara.triagem.infra.RelogioAjustavel.ClockMovel;
import com.joaoalcantara.triagem.infra.TesteDeIntegracao;
import com.joaoalcantara.triagem.triagem.TriadorDeChamados;

/**
 * Circuit breaker por provedor: depois de cinco falhas seguidas, o Gemini e
 * pulado sem requisicao nenhuma durante a janela de um minuto.
 *
 * <p>Cada tentativa conta para o circuito. Com o Gemini sempre fora e tres
 * tentativas por triagem, o primeiro chamado gasta tres requisicoes; o segundo,
 * duas — a quinta falha abre o circuito, e a terceira tentativa ja encontra o
 * circuito aberto. Dali em diante, zero.</p>
 *
 * <p>A janela de tempo aberto passa no relogio da aplicacao, que o teste avanca:
 * nada de esperar um minuto de verdade.</p>
 */
@TesteDeIntegracao
class DisjuntorIT {

    private static final int FALHAS_PARA_ABRIR = 5;

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
    private PropriedadesDoDisjuntor disjuntor;

    @Autowired
    private ClockMovel relogio;

    @BeforeEach
    void limpar() {
        ambiente.limpar();
        groq.stubFor(ProvedorFalso.chat().willReturn(valida()));
    }

    private static ResponseDefinitionBuilder valida() {
        return ProvedorFalso.classifica("TECNICO", "ALTA", "Relatorio nao abre");
    }

    private static ResponseDefinitionBuilder foraDoAr() {
        return aResponse().withStatus(503).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"indisponivel\",\"code\":503}}");
    }

    private int triagens;

    /**
     * Texto diferente a cada triagem: com o mesmo texto, a partir da primeira
     * classificacao bem-sucedida o cache responderia, e nenhum provedor seria
     * chamado — o teste deixaria de exercitar o circuito.
     */
    private Chamado triar() {
        UUID id = servico.registrar("O relatorio mensal nao abre desde ontem (%d).".formatted(++triagens)).getId();
        triador.processarProximo();
        return repositorio.findById(id).orElseThrow();
    }

    private int requisicoesAoGemini() {
        return gemini.findAll(postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO))).size();
    }

    /** Triagens com o Gemini fora ate o circuito abrir. */
    private void abrirOCircuitoDoGemini() {
        gemini.stubFor(ProvedorFalso.chat().willReturn(foraDoAr()));
        triar();
        triar();
        assertThat(requisicoesAoGemini()).isEqualTo(FALHAS_PARA_ABRIR);
    }

    @Test
    @DisplayName("circuito aberto: zero requisicoes ao Gemini durante a janela, e os chamados seguem triados pelo Groq")
    void circuitoAbertoNaoChamaOProvedor() {
        abrirOCircuitoDoGemini();

        for (int i = 0; i < 5; i++) {
            assertThat(triar().getProvedor()).isEqualTo("groq");
        }

        assertThat(requisicoesAoGemini()).isEqualTo(FALHAS_PARA_ABRIR);
    }

    @Test
    @DisplayName("circuito aberto: o chamado nao paga mais o tempo limite do provedor lento")
    void circuitoAbertoNaoEsperaOTempoLimite() {
        gemini.stubFor(ProvedorFalso.chat().willReturn(valida().withFixedDelay(5_000)));
        triar();
        triar();

        long inicio = System.nanoTime();
        Chamado chamado = triar();
        Duration tempo = Duration.ofNanos(System.nanoTime() - inicio);

        assertThat(chamado.getProvedor()).isEqualTo("groq");
        // Com o circuito fechado, seriam 3 x 1s de tempo limite mais as esperas.
        assertThat(tempo).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("passada a janela, uma chamada de teste ao Gemini; se der certo, o circuito fecha")
    void meioAbertoComSucessoFecha() {
        abrirOCircuitoDoGemini();
        gemini.resetAll();
        gemini.stubFor(ProvedorFalso.chat().willReturn(valida()));

        relogio.avancar(disjuntor.tempoAberto().plusSeconds(1));

        assertThat(triar().getProvedor()).isEqualTo("gemini");
        assertThat(triar().getProvedor()).isEqualTo("gemini");
        assertThat(requisicoesAoGemini()).isEqualTo(2);
    }

    @Test
    @DisplayName("passada a janela, se a chamada de teste falhar, o circuito reabre sem mais nenhuma requisicao")
    void meioAbertoComFalhaReabre() {
        abrirOCircuitoDoGemini();

        relogio.avancar(disjuntor.tempoAberto().plusSeconds(1));

        assertThat(triar().getProvedor()).isEqualTo("groq");
        assertThat(requisicoesAoGemini()).isEqualTo(FALHAS_PARA_ABRIR + 1);

        assertThat(triar().getProvedor()).isEqualTo("groq");
        assertThat(requisicoesAoGemini()).isEqualTo(FALHAS_PARA_ABRIR + 1);
    }

    @Test
    @DisplayName("antes da janela acabar, o circuito continua aberto")
    void janelaAindaAberta() {
        abrirOCircuitoDoGemini();

        relogio.avancar(disjuntor.tempoAberto().minusSeconds(1));

        assertThat(triar().getProvedor()).isEqualTo("groq");
        assertThat(requisicoesAoGemini()).isEqualTo(FALHAS_PARA_ABRIR);
    }

    @Test
    @DisplayName("uma resposta boa no meio zera a contagem: provedor instavel nao e pulado")
    void respostaBoaZeraAContagem() {
        gemini.stubFor(ProvedorFalso.chat().inScenario("instavel").whenScenarioStateIs("Started")
                .willReturn(foraDoAr()).willSetStateTo("falhou-1"));
        gemini.stubFor(ProvedorFalso.chat().inScenario("instavel").whenScenarioStateIs("falhou-1")
                .willReturn(foraDoAr()).willSetStateTo("falhou-2"));
        gemini.stubFor(ProvedorFalso.chat().inScenario("instavel").whenScenarioStateIs("falhou-2")
                .willReturn(valida()).willSetStateTo("Started"));

        // Cada triagem: duas falhas e um sucesso na terceira tentativa. Quatro
        // triagens somam oito falhas, mas nunca cinco seguidas.
        for (int i = 0; i < 4; i++) {
            assertThat(triar().getProvedor()).isEqualTo("gemini");
        }
        assertThat(requisicoesAoGemini()).isEqualTo(12);
    }
}
