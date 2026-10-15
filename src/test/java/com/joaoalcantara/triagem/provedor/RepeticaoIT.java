package com.joaoalcantara.triagem.provedor;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.joaoalcantara.triagem.chamado.ChamadoRepositorio;
import com.joaoalcantara.triagem.chamado.ChamadoServico;
import com.joaoalcantara.triagem.chamado.StatusChamado;
import com.joaoalcantara.triagem.infra.ProvedorFalso;
import com.joaoalcantara.triagem.infra.TesteDeIntegracao;
import com.joaoalcantara.triagem.triagem.TriadorDeChamados;

/**
 * Tempo limite e nova tentativa contra um provedor que demora, cai e estoura a
 * cota.
 *
 * <p>Configuracao dos testes ({@code @TesteDeIntegracao}): tempo limite de 1s,
 * tres tentativas, espera inicial de 100ms e espera maxima de 2s. As esperas
 * acontecem de verdade; os intervalos sao medidos pelo horario em que cada
 * requisicao chegou ao WireMock.</p>
 */
@TesteDeIntegracao
class RepeticaoIT {

    private static final String TEXTO = "O painel administrativo esta fora do ar para todos os usuarios.";

    @Autowired
    private TriadorDeChamados triador;

    @Autowired
    private ChamadoServico servico;

    @Autowired
    private ChamadoRepositorio repositorio;

    @Autowired
    private WireMockServer gemini;

    @BeforeEach
    void limpar() {
        repositorio.deleteAll();
        gemini.resetAll();
    }

    private static ResponseDefinitionBuilder valida() {
        return ProvedorFalso.classifica("TECNICO", "CRITICA", "Painel administrativo fora do ar");
    }

    private static ResponseDefinitionBuilder status(int codigo) {
        return aResponse().withStatus(codigo)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"falha simulada\",\"code\":%d}}".formatted(codigo));
    }

    /** Primeira requisicao recebe {@code primeira}; as seguintes, {@code depois}. */
    private void respondeEmSequencia(ResponseDefinitionBuilder primeira, ResponseDefinitionBuilder depois) {
        gemini.stubFor(ProvedorFalso.chat().inScenario("repeticao")
                .whenScenarioStateIs(STARTED).willReturn(primeira).willSetStateTo("depois"));
        gemini.stubFor(ProvedorFalso.chat().inScenario("repeticao")
                .whenScenarioStateIs("depois").willReturn(depois));
    }

    private StatusChamado triar() {
        UUID id = servico.registrar(TEXTO).getId();
        triador.processarProximo();
        return repositorio.findById(id).orElseThrow().getStatus();
    }

    private Duration cronometrar(Runnable acao) {
        long inicio = System.nanoTime();
        acao.run();
        return Duration.ofNanos(System.nanoTime() - inicio);
    }

    private List<LoggedRequest> requisicoes() {
        return gemini.findAll(postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO)));
    }

    private Duration intervaloEntre(LoggedRequest anterior, LoggedRequest seguinte) {
        return Duration.ofMillis(seguinte.getLoggedDate().getTime() - anterior.getLoggedDate().getTime());
    }

    private void verificarRequisicoes(int quantidade) {
        gemini.verify(exactly(quantidade), postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO)));
    }

    // --- Falhas transitorias: tentar de novo ---

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504, 408})
    @DisplayName("falha transitoria seguida de sucesso: o chamado e triado na segunda tentativa")
    void transitoriaSeguidaDeSucesso(int codigo) {
        respondeEmSequencia(status(codigo), valida());

        assertThat(triar()).isEqualTo(StatusChamado.TRIADO);
        verificarRequisicoes(2);
    }

    @Test
    @DisplayName("tres falhas seguidas: triagem manual depois de exatamente tres requisicoes")
    void tresFalhasSeguidas() {
        gemini.stubFor(ProvedorFalso.chat().willReturn(status(503)));

        assertThat(triar()).isEqualTo(StatusChamado.PENDENTE_TRIAGEM_MANUAL);
        // Tres, e nao doze: se o SDK tambem repetisse (o padrao dele), cada
        // tentativa nossa viraria quatro requisicoes.
        verificarRequisicoes(3);
    }

    @Test
    @DisplayName("ha espera entre as tentativas: o provedor nao e martelado")
    void esperaEntreTentativas() {
        gemini.stubFor(ProvedorFalso.chat().willReturn(status(503)));

        triar();

        List<LoggedRequest> requisicoes = requisicoes();
        assertThat(requisicoes).hasSize(3);
        // Espera inicial de 100ms com 50% de variacao: pelo menos 50ms. Depois dobra.
        assertThat(intervaloEntre(requisicoes.get(0), requisicoes.get(1))).isGreaterThanOrEqualTo(Duration.ofMillis(50));
        assertThat(intervaloEntre(requisicoes.get(1), requisicoes.get(2))).isGreaterThanOrEqualTo(Duration.ofMillis(100));
    }

    // --- Falhas definitivas: nao repetir ---

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 422})
    @DisplayName("falha definitiva nao se repete: uma unica requisicao")
    void definitivaNaoSeRepete(int codigo) {
        gemini.stubFor(ProvedorFalso.chat().willReturn(status(codigo)));

        assertThat(triar()).isEqualTo(StatusChamado.PENDENTE_TRIAGEM_MANUAL);
        verificarRequisicoes(1);
    }

    // --- 429 e Retry-After ---

    @Test
    @DisplayName("429 com Retry-After: a nova tentativa espera o que o provedor pediu, nem um instante antes")
    void retryAfterRespeitado() {
        respondeEmSequencia(status(429).withHeader("Retry-After", "1"), valida());

        assertThat(triar()).isEqualTo(StatusChamado.TRIADO);

        List<LoggedRequest> requisicoes = requisicoes();
        assertThat(requisicoes).hasSize(2);
        // Sem o Retry-After, a espera seria a inicial: cerca de 100ms.
        assertThat(intervaloEntre(requisicoes.get(0), requisicoes.get(1))).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("429 pedindo mais que a espera maxima: nao espera nem repete, a falha segue na hora")
    void retryAfterAlemDoMaximo() {
        gemini.stubFor(ProvedorFalso.chat().willReturn(status(429).withHeader("Retry-After", "60")));

        Duration tempo = cronometrar(() -> assertThat(triar()).isEqualTo(StatusChamado.PENDENTE_TRIAGEM_MANUAL));

        verificarRequisicoes(1);
        assertThat(tempo).isLessThan(Duration.ofSeconds(5));
    }

    /**
     * 429 no formato real do Gemini, capturado da API: sem cabecalho
     * Retry-After, com a espera no RetryInfo do corpo, e o erro dentro de um array.
     */
    private static ResponseDefinitionBuilder cotaDoGeminiEsgotada(String retryDelay) {
        return aResponse().withStatus(429)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        [{"error": {"code": 429,
                          "message": "You exceeded your current quota. Please retry in %s.",
                          "status": "RESOURCE_EXHAUSTED",
                          "details": [
                            {"@type": "type.googleapis.com/google.rpc.QuotaFailure",
                             "violations": [{"quotaId": "GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]},
                            {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "%s"}]}}]
                        """.formatted(retryDelay, retryDelay));
    }

    @Test
    @DisplayName("429 do Gemini, com a espera no corpo e nao no cabecalho: a espera pedida e respeitada")
    void retryDelayDoGeminiRespeitado() {
        respondeEmSequencia(cotaDoGeminiEsgotada("1s"), valida());

        assertThat(triar()).isEqualTo(StatusChamado.TRIADO);

        List<LoggedRequest> requisicoes = requisicoes();
        assertThat(requisicoes).hasSize(2);
        assertThat(intervaloEntre(requisicoes.get(0), requisicoes.get(1))).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("429 do Gemini pedindo 26s: nada de tentar em 1s e 2s para receber mais dois 429")
    void retryDelayDoGeminiAlemDoMaximo() {
        gemini.stubFor(ProvedorFalso.chat().willReturn(cotaDoGeminiEsgotada("26.787526249s")));

        Duration tempo = cronometrar(() -> assertThat(triar()).isEqualTo(StatusChamado.PENDENTE_TRIAGEM_MANUAL));

        verificarRequisicoes(1);
        assertThat(tempo).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("429 sem espera informada: repete com a espera crescente")
    void rateLimitSemRetryAfter() {
        respondeEmSequencia(status(429), valida());

        assertThat(triar()).isEqualTo(StatusChamado.TRIADO);
        verificarRequisicoes(2);
    }

    // --- Lentidao ---

    @Test
    @DisplayName("provedor lento: a chamada e abandonada no tempo limite e a nova tentativa resolve")
    void lentoUmaVez() {
        respondeEmSequencia(valida().withFixedDelay(5_000), valida());

        Duration tempo = cronometrar(() -> assertThat(triar()).isEqualTo(StatusChamado.TRIADO));

        verificarRequisicoes(2);
        // Tempo limite de 1s mais a espera: bem menos que os 5s da resposta lenta.
        assertThat(tempo).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    @DisplayName("provedor sempre lento: tres tentativas cortadas no tempo limite, e triagem manual")
    void sempreLento() {
        gemini.stubFor(ProvedorFalso.chat().willReturn(valida().withFixedDelay(5_000)));

        Duration tempo = cronometrar(() -> assertThat(triar()).isEqualTo(StatusChamado.PENDENTE_TRIAGEM_MANUAL));

        verificarRequisicoes(3);
        // 3 x 1s de tempo limite + esperas. Esperando a resposta inteira seriam 15s.
        assertThat(tempo).isLessThan(Duration.ofSeconds(8));
    }
}
