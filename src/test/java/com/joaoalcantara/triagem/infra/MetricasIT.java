package com.joaoalcantara.triagem.infra;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.joaoalcantara.triagem.chamado.ChamadoServico;
import com.joaoalcantara.triagem.custo.PropriedadesDoCusto;
import com.joaoalcantara.triagem.provedor.PropriedadesDosProvedores;
import com.joaoalcantara.triagem.triagem.TriadorDeChamados;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Statistic;

/**
 * As metricas mudam quando o que elas medem acontece.
 *
 * <p>Observabilidade tem um problema proprio: so e exercitada quando alguem vai
 * investigar um incidente, e descobrir na hora que o contador nunca saiu do
 * zero nao ajuda ninguem. Por isso ela tambem tem teste.</p>
 */
/*
 * O Spring Boot desliga a exportacao de metricas nos testes por padrao, e sem
 * @AutoConfigureMetrics o /actuator/prometheus responde 404 mesmo com tudo
 * configurado para producao. O sintoma parece configuracao errada, e nao e.
 */
@TesteDeIntegracao
@AutoConfigureMockMvc
@AutoConfigureMetrics
class MetricasIT {

    @Autowired
    private AmbienteDeTeste ambiente;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry registro;

    @Autowired
    private TriadorDeChamados triador;

    @Autowired
    private ChamadoServico servico;

    @Autowired
    private WireMockServer gemini;

    @Autowired
    private WireMockServer groq;

    @Autowired
    private CircuitBreakerRegistry disjuntores;

    @Autowired
    private JdbcClient banco;

    @Autowired
    private PropriedadesDosProvedores provedores;

    @Autowired
    private PropriedadesDoCusto custo;

    private int triagens;

    @BeforeEach
    void limpar() {
        ambiente.limpar();
        gemini.stubFor(ProvedorFalso.chat().willReturn(ProvedorFalso.classifica("DUVIDA", "BAIXA", "Duvida de uso")));
        groq.stubFor(ProvedorFalso.chat().willReturn(ProvedorFalso.classifica("DUVIDA", "BAIXA", "Duvida de uso")));
    }

    private void triar() {
        triar("Como exporto o relatorio %d em PDF?".formatted(++triagens));
    }

    private void triar(String texto) {
        servico.registrar(texto);
        triador.processarProximo();
    }

    /** Quantas vezes o medidor registrou algo (contador ou timer), somando as series que casam. */
    private double vezes(String nome, String... etiquetas) {
        return registro.find(nome).tags(etiquetas).meters().stream()
                .flatMap(medidor -> java.util.stream.StreamSupport.stream(medidor.measure().spliterator(), false))
                .filter(medida -> medida.getStatistic() == Statistic.COUNT)
                .mapToDouble(Measurement::getValue)
                .sum();
    }

    private double valorDoMedidor(String nome, String... etiquetas) {
        return registro.get(nome).tags(etiquetas).gauge().value();
    }

    @Test
    @DisplayName("triagem pelo principal: a requisicao, os tokens e a rodada sao contados")
    void triagemPeloPrincipal() {
        double requisicoes = vezes("triagem.provedor.requisicoes", "provedor", "gemini", "resultado", "sucesso");
        double entrada = vezes("triagem.provedor.tokens", "provedor", "gemini", "tipo", "entrada");
        double saida = vezes("triagem.provedor.tokens", "provedor", "gemini", "tipo", "saida");
        double rodadas = vezes("triagem.triagens", "resultado", "triado", "provedor", "gemini", "fallback", "false");

        triar();

        assertThat(vezes("triagem.provedor.requisicoes", "provedor", "gemini", "resultado", "sucesso"))
                .isEqualTo(requisicoes + 1);
        // O ProvedorFalso responde com uso de 120 tokens de entrada e 30 de saida.
        assertThat(vezes("triagem.provedor.tokens", "provedor", "gemini", "tipo", "entrada")).isEqualTo(entrada + 120);
        assertThat(vezes("triagem.provedor.tokens", "provedor", "gemini", "tipo", "saida")).isEqualTo(saida + 30);
        assertThat(vezes("triagem.triagens", "resultado", "triado", "provedor", "gemini", "fallback", "false"))
                .isEqualTo(rodadas + 1);
    }

    @Test
    @DisplayName("fallback: a falha do principal e a rodada resolvida pelo secundario aparecem separadas")
    void fallback() {
        gemini.resetAll();
        gemini.stubFor(ProvedorFalso.chat().willReturn(aResponse().withStatus(400)));
        double definitivas = vezes("triagem.provedor.requisicoes", "provedor", "gemini", "resultado", "definitiva");
        double comFallback = vezes("triagem.triagens", "provedor", "groq", "fallback", "true");

        triar();

        assertThat(vezes("triagem.provedor.requisicoes", "provedor", "gemini", "resultado", "definitiva"))
                .isEqualTo(definitivas + 1);
        assertThat(vezes("triagem.triagens", "provedor", "groq", "fallback", "true")).isEqualTo(comFallback + 1);
    }

    @Test
    @DisplayName("acerto de cache e contado com o provedor que classificou originalmente")
    void acertoDeCache() {
        double acertos = vezes("triagem.cache", "resultado", "acerto", "provedor", "gemini");
        double faltas = vezes("triagem.cache", "resultado", "falta");
        double rodadasDoCache = vezes("triagem.triagens", "cache", "true");

        triar("Texto repetido para o cache.");
        triar("Texto repetido para o cache.");

        assertThat(vezes("triagem.cache", "resultado", "falta")).isEqualTo(faltas + 1);
        assertThat(vezes("triagem.cache", "resultado", "acerto", "provedor", "gemini")).isEqualTo(acertos + 1);
        assertThat(vezes("triagem.triagens", "cache", "true")).isEqualTo(rodadasDoCache + 1);
    }

    @Test
    @DisplayName("resposta invalida e contada na primeira tentativa e na correcao")
    void respostaInvalida() {
        gemini.resetAll();
        gemini.stubFor(ProvedorFalso.chat().willReturn(ProvedorFalso.responde("nao e JSON")));
        double primeiras = vezes("triagem.respostas.invalidas", "provedor", "gemini", "tentativa", "primeira");
        double correcoes = vezes("triagem.respostas.invalidas", "provedor", "gemini", "tentativa", "correcao");

        triar();

        assertThat(vezes("triagem.respostas.invalidas", "provedor", "gemini", "tentativa", "primeira"))
                .isEqualTo(primeiras + 1);
        assertThat(vezes("triagem.respostas.invalidas", "provedor", "gemini", "tentativa", "correcao"))
                .isEqualTo(correcoes + 1);
    }

    @Test
    @DisplayName("provedor pulado por circuito aberto ou orcamento esgotado e contado, sem contar requisicao")
    void provedorPulado() {
        double requisicoes = vezes("triagem.provedor.requisicoes", "provedor", "gemini");
        double porCircuito = vezes("triagem.provedor.pulados", "provedor", "gemini", "motivo", "circuito_aberto");
        double porOrcamento = vezes("triagem.provedor.pulados", "provedor", "groq", "motivo", "orcamento_esgotado");

        disjuntores.circuitBreaker("gemini").transitionToOpenState();
        banco.sql("INSERT INTO uso_diario_dos_provedores (provedor, dia, requisicoes) VALUES ('groq', :dia, :usadas)")
                .param("dia", LocalDate.now(ZoneId.of("UTC")))
                .param("usadas", provedores.groq().orcamentoDiario())
                .update();
        triar();

        assertThat(vezes("triagem.provedor.pulados", "provedor", "gemini", "motivo", "circuito_aberto"))
                .isEqualTo(porCircuito + 1);
        assertThat(vezes("triagem.provedor.pulados", "provedor", "groq", "motivo", "orcamento_esgotado"))
                .isEqualTo(porOrcamento + 1);
        assertThat(vezes("triagem.provedor.requisicoes", "provedor", "gemini")).isEqualTo(requisicoes);
    }

    @Test
    @DisplayName("rodada sem nenhum provedor resolvendo conta como pendente")
    void pendente() {
        gemini.resetAll();
        groq.resetAll();
        double pendentes = vezes("triagem.triagens", "resultado", "pendente", "provedor", "nenhum");

        triar();

        assertThat(vezes("triagem.triagens", "resultado", "pendente", "provedor", "nenhum")).isEqualTo(pendentes + 1);
    }

    @Test
    @DisplayName("os medidores mostram a fila e o orcamento usado agora")
    void medidores() {
        servico.registrar("Chamado que ainda espera triagem.");
        assertThat(valorDoMedidor("triagem.fila", "situacao", "recebidos")).isEqualTo(1.0);

        triador.processarProximo();

        assertThat(valorDoMedidor("triagem.fila", "situacao", "recebidos")).isZero();
        assertThat(valorDoMedidor("triagem.orcamento.usado", "provedor", "gemini")).isEqualTo(1.0);
        assertThat(valorDoMedidor("triagem.orcamento.limite", "provedor", "gemini"))
                .isEqualTo(provedores.gemini().orcamentoDiario());
    }

    @Test
    @DisplayName("chamado recusado pelo limite por cliente e contado")
    void recusadoPeloLimite() throws Exception {
        double recusados = vezes("triagem.chamados.recusados", "motivo", "limite_por_cliente");

        for (int i = 0; i <= custo.chamadosPorHora(); i++) {
            mockMvc.perform(post("/api/chamados").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"texto\":\"Chamado %d.\"}".formatted(i)));
        }

        assertThat(vezes("triagem.chamados.recusados", "motivo", "limite_por_cliente")).isEqualTo(recusados + 1);
    }

    @Test
    @DisplayName("o /actuator/prometheus publica as metricas da triagem e dos circuitos, e nunca o texto dos chamados")
    void endpointDoPrometheus() throws Exception {
        String textoDistinto = "Texto com uma marca bem distinta: zebra-azul-42.";
        triar(textoDistinto);

        String pagina = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(pagina)
                .contains("triagem_triagens_seconds_count{")
                .contains("triagem_provedor_requisicoes_seconds_bucket{")
                .contains("triagem_provedor_tokens_total{")
                .contains("triagem_cache_total{")
                .contains("triagem_fila{")
                .contains("triagem_orcamento_usado{")
                .contains("resilience4j_circuitbreaker_state{")
                .contains("aplicacao=\"triagem\"")
                .doesNotContain("zebra-azul-42");
    }
}
