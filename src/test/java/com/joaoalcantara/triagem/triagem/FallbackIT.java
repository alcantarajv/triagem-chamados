package com.joaoalcantara.triagem.triagem;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
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
import com.joaoalcantara.triagem.chamado.StatusChamado;
import com.joaoalcantara.triagem.infra.AmbienteDeTeste;
import com.joaoalcantara.triagem.infra.ProvedorFalso;
import com.joaoalcantara.triagem.infra.TesteDeIntegracao;

/**
 * A cadeia de provedores: quando o Gemini nao resolve, o Groq responde.
 *
 * <p>Configuracao dos testes: tempo limite de 1s e tres tentativas por provedor.</p>
 */
@TesteDeIntegracao
class FallbackIT {

    private static final String TEXTO = "Nao recebi o codigo de verificacao por SMS e nao consigo entrar.";

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

    @BeforeEach
    void limpar() {
        ambiente.limpar();
    }

    private static ResponseDefinitionBuilder valida() {
        return ProvedorFalso.classifica("ACESSO", "ALTA", "Codigo de verificacao por SMS nao chega");
    }

    private static ResponseDefinitionBuilder status(int codigo) {
        return aResponse().withStatus(codigo)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"falha simulada\",\"code\":%d}}".formatted(codigo));
    }

    private Chamado triar() {
        UUID id = servico.registrar(TEXTO).getId();
        triador.processarProximo();
        return repositorio.findById(id).orElseThrow();
    }

    private Duration cronometrar(Runnable acao) {
        long inicio = System.nanoTime();
        acao.run();
        return Duration.ofNanos(System.nanoTime() - inicio);
    }

    private static void verificar(WireMockServer provedor, int requisicoes) {
        provedor.verify(exactly(requisicoes), postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO)));
    }

    @Test
    @DisplayName("principal respondendo: o fallback nem e consultado")
    void principalResolve() {
        gemini.stubFor(ProvedorFalso.chat().willReturn(valida()));
        groq.stubFor(ProvedorFalso.chat().willReturn(valida()));

        assertThat(triar().getProvedor()).isEqualTo("gemini");
        verificar(groq, 0);
    }

    @Test
    @DisplayName("principal mais lento que o tempo limite: o secundario responde e o chamado fica TRIADO")
    void principalLento() {
        gemini.stubFor(ProvedorFalso.chat().willReturn(valida().withFixedDelay(5_000)));
        groq.stubFor(ProvedorFalso.chat().willReturn(valida()));

        Chamado chamado = triar();

        assertThat(chamado.getStatus()).isEqualTo(StatusChamado.TRIADO);
        assertThat(chamado.getProvedor()).isEqualTo("groq");
        verificar(gemini, 3);
        verificar(groq, 1);
    }

    @Test
    @DisplayName("principal com cota esgotada pedindo 26s: o secundario responde na hora, sem esperar nem insistir")
    void principalComCotaEsgotada() {
        gemini.stubFor(ProvedorFalso.chat().willReturn(status(429).withHeader("Retry-After", "26")));
        groq.stubFor(ProvedorFalso.chat().willReturn(valida()));

        Duration tempo = cronometrar(() -> assertThat(triar().getProvedor()).isEqualTo("groq"));

        verificar(gemini, 1);
        assertThat(tempo).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("principal fora do ar: depois das tentativas dele, o secundario responde")
    void principalForaDoAr() {
        gemini.stubFor(ProvedorFalso.chat().willReturn(status(503)));
        groq.stubFor(ProvedorFalso.chat().willReturn(valida()));

        assertThat(triar().getProvedor()).isEqualTo("groq");
        verificar(gemini, 3);
    }

    @Test
    @DisplayName("chave do principal recusada: uma requisicao so, e o secundario responde")
    void principalComChaveRecusada() {
        gemini.stubFor(ProvedorFalso.chat().willReturn(status(401)));
        groq.stubFor(ProvedorFalso.chat().willReturn(valida()));

        assertThat(triar().getProvedor()).isEqualTo("groq");
        verificar(gemini, 1);
    }

    @Test
    @DisplayName("principal invalido mesmo depois da correcao: o secundario e consultado do zero")
    void principalInvalidoDuasVezes() {
        gemini.stubFor(ProvedorFalso.chat().willReturn(ProvedorFalso.classifica("URGENTE", "ALTA", "SMS nao chega")));
        groq.stubFor(ProvedorFalso.chat().willReturn(valida()));

        Chamado chamado = triar();

        assertThat(chamado.getProvedor()).isEqualTo("groq");
        verificar(gemini, 2);
        // O Groq recebe o pedido original, nao a conversa de correcao do Gemini.
        groq.verify(exactly(1), postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO))
                .withRequestBody(matchingJsonPath("$.messages.length()", equalTo("2")))
                .withRequestBody(matchingJsonPath("$.model", equalTo(ProvedorFalso.MODELO_GROQ))));
    }

    @Test
    @DisplayName("todos os provedores fora: triagem manual, sem perder o chamado")
    void todosFora() {
        gemini.stubFor(ProvedorFalso.chat().willReturn(status(503)));
        groq.stubFor(ProvedorFalso.chat().willReturn(status(503)));

        Chamado chamado = triar();

        assertThat(chamado.getStatus()).isEqualTo(StatusChamado.PENDENTE_TRIAGEM_MANUAL);
        assertThat(chamado.getTexto()).isEqualTo(TEXTO);
        verificar(gemini, 3);
        verificar(groq, 3);
    }
}
