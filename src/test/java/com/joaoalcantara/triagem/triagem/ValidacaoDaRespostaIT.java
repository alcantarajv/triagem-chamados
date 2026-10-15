package com.joaoalcantara.triagem.triagem;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.joaoalcantara.triagem.chamado.Categoria;
import com.joaoalcantara.triagem.chamado.Chamado;
import com.joaoalcantara.triagem.chamado.ChamadoRepositorio;
import com.joaoalcantara.triagem.chamado.ChamadoServico;
import com.joaoalcantara.triagem.chamado.StatusChamado;
import com.joaoalcantara.triagem.infra.ProvedorFalso;
import com.joaoalcantara.triagem.infra.TesteDeIntegracao;

/**
 * A resposta do modelo nao e confiavel: o que acontece quando ela vem fora do
 * combinado.
 *
 * <p>Cada teste conta as requisicoes que chegaram ao provedor. "Exatamente
 * duas" e o que prova a regra: uma correcao, e nunca uma terceira chamada.</p>
 */
@TesteDeIntegracao
class ValidacaoDaRespostaIT {

    private static final String TEXTO = "Fui cobrado duas vezes pela mensalidade de outubro.";
    private static final String CORRECAO = "correcao";

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

    /** Primeira requisicao recebe {@code primeira}; as seguintes, {@code depois}. */
    private void respondeEmSequencia(ResponseDefinitionBuilder primeira, ResponseDefinitionBuilder depois) {
        gemini.stubFor(ProvedorFalso.chat().inScenario(CORRECAO)
                .whenScenarioStateIs(STARTED)
                .willReturn(primeira)
                .willSetStateTo("respondida"));
        gemini.stubFor(ProvedorFalso.chat().inScenario(CORRECAO)
                .whenScenarioStateIs("respondida")
                .willReturn(depois));
    }

    private Chamado triar() {
        UUID id = servico.registrar(TEXTO).getId();
        triador.processarProximo();
        return repositorio.findById(id).orElseThrow();
    }

    private void verificarRequisicoes(int quantidade) {
        gemini.verify(exactly(quantidade), postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO)));
    }

    @Test
    @DisplayName("categoria inventada: uma nova tentativa com o erro, e a correcao e aceita")
    void categoriaInventadaECorrigida() {
        respondeEmSequencia(
                ProvedorFalso.classifica("URGENTE", "ALTA", "Cobranca duplicada da mensalidade"),
                ProvedorFalso.classifica("FINANCEIRO", "ALTA", "Cobranca duplicada da mensalidade"));

        Chamado chamado = triar();

        assertThat(chamado.getStatus()).isEqualTo(StatusChamado.TRIADO);
        assertThat(chamado.getCategoria()).isEqualTo(Categoria.FINANCEIRO);
        verificarRequisicoes(2);
    }

    @Test
    @DisplayName("a nova tentativa mostra ao modelo a resposta recusada e o motivo")
    void correcaoLevaRespostaEMotivo() {
        respondeEmSequencia(
                ProvedorFalso.classifica("URGENTE", "ALTA", "Cobranca duplicada"),
                ProvedorFalso.classifica("FINANCEIRO", "ALTA", "Cobranca duplicada"));

        triar();

        // Conversa da correcao: sistema, chamado, resposta recusada (como fala do
        // proprio modelo) e os motivos da recusa.
        gemini.verify(exactly(1), postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO))
                .withRequestBody(matchingJsonPath("$.messages.length()", equalTo("4")))
                .withRequestBody(matchingJsonPath("$.messages[2].role", equalTo("assistant")))
                .withRequestBody(matchingJsonPath("$.messages[3].role", equalTo("user")))
                .withRequestBody(containing("URGENTE"))
                .withRequestBody(containing("categoria 'URGENTE' nao existe; use um destes valores"))
                .withRequestBody(containing(TEXTO)));
        // E a primeira requisicao nao tinha nada disso.
        gemini.verify(exactly(1), postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO))
                .withRequestBody(matchingJsonPath("$.messages.length()", equalTo("2")))
                .withRequestBody(notContaining("recusada")));
    }

    @Test
    @DisplayName("texto solto e depois JSON valido: a correcao e aceita")
    void textoSoltoECorrigido() {
        respondeEmSequencia(
                ProvedorFalso.responde("Claro! Esse chamado e sobre cobranca."),
                ProvedorFalso.classifica("FINANCEIRO", "MEDIA", "Cobranca duplicada"));

        assertThat(triar().getStatus()).isEqualTo(StatusChamado.TRIADO);
        verificarRequisicoes(2);
    }

    @Test
    @DisplayName("JSON invalido duas vezes: triagem manual, sem terceira chamada")
    void invalidoDuasVezes() {
        respondeEmSequencia(
                ProvedorFalso.responde("{\"categoria\": \"FINANCEIRO\", \"prioridade\":"),
                ProvedorFalso.responde("{\"categoria\": \"FINANCEIRO\", \"prioridade\":"));

        Chamado chamado = triar();

        assertThat(chamado.getStatus()).isEqualTo(StatusChamado.PENDENTE_TRIAGEM_MANUAL);
        assertThat(chamado.getCategoria()).isNull();
        assertThat(chamado.getTexto()).isEqualTo(TEXTO);
        verificarRequisicoes(2);
    }

    @Test
    @DisplayName("categoria inventada duas vezes: triagem manual, sem terceira chamada")
    void categoriaInventadaDuasVezes() {
        respondeEmSequencia(
                ProvedorFalso.classifica("URGENTE", "ALTA", "Cobranca duplicada"),
                ProvedorFalso.classifica("COBRANCA", "ALTA", "Cobranca duplicada"));

        assertThat(triar().getStatus()).isEqualTo(StatusChamado.PENDENTE_TRIAGEM_MANUAL);
        verificarRequisicoes(2);
    }

    @Test
    @DisplayName("resumo longo demais e corrigido, sem chegar ao banco")
    void resumoLongoDemais() {
        respondeEmSequencia(
                ProvedorFalso.classifica("FINANCEIRO", "ALTA", "x".repeat(500)),
                ProvedorFalso.classifica("FINANCEIRO", "ALTA", "Cobranca duplicada da mensalidade"));

        Chamado chamado = triar();

        assertThat(chamado.getStatus()).isEqualTo(StatusChamado.TRIADO);
        assertThat(chamado.getResumo()).isEqualTo("Cobranca duplicada da mensalidade");
        gemini.verify(exactly(1), postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO))
                .withRequestBody(containing("o resumo tem 500 caracteres; o maximo e 120")));
    }

    @Test
    @DisplayName("resposta enorme volta truncada na correcao: a cota nao paga duas vezes pelo lixo")
    void respostaEnormeVoltaTruncada() {
        String enorme = "y".repeat(5000);
        respondeEmSequencia(
                ProvedorFalso.responde(enorme),
                ProvedorFalso.classifica("FINANCEIRO", "ALTA", "Cobranca duplicada"));

        assertThat(triar().getStatus()).isEqualTo(StatusChamado.TRIADO);
        gemini.verify(exactly(1), postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO))
                .withRequestBody(containing("y".repeat(1000) + "..."))
                .withRequestBody(notContaining("y".repeat(1001))));
    }
}
