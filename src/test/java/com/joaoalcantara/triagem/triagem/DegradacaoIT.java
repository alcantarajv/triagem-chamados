package com.joaoalcantara.triagem.triagem;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.jayway.jsonpath.JsonPath;
import com.joaoalcantara.triagem.chamado.Chamado;
import com.joaoalcantara.triagem.chamado.ChamadoRepositorio;
import com.joaoalcantara.triagem.chamado.ChamadoServico;
import com.joaoalcantara.triagem.chamado.StatusChamado;
import com.joaoalcantara.triagem.infra.AmbienteDeTeste;
import com.joaoalcantara.triagem.infra.ProvedorFalso;
import com.joaoalcantara.triagem.infra.RelogioAjustavel.ClockMovel;
import com.joaoalcantara.triagem.infra.TesteDeIntegracao;

/**
 * Todos os provedores fora: nada se perde, a API continua respondendo, e o
 * revisor de pendentes tenta de novo mais tarde, com espera crescente.
 *
 * <p>Configuracao padrao das esperas: 5 minutos, 30 minutos e 2 horas. O tempo
 * passa no relogio da aplicacao, que o teste avanca.</p>
 */
@TesteDeIntegracao
@AutoConfigureMockMvc
class DegradacaoIT {

    private static final String TEXTO = "O sistema de ponto nao registra a minha saida desde segunda-feira.";

    @Autowired
    private AmbienteDeTeste ambiente;

    @Autowired
    private MockMvc mockMvc;

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
    private ClockMovel relogio;

    @BeforeEach
    void limpar() {
        ambiente.limpar();
    }

    private static ResponseDefinitionBuilder foraDoAr() {
        return aResponse().withStatus(503).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"indisponivel\",\"code\":503}}");
    }

    private static ResponseDefinitionBuilder valida() {
        return ProvedorFalso.classifica("TECNICO", "MEDIA", "Ponto eletronico nao registra a saida");
    }

    private void todosForaDoAr() {
        gemini.stubFor(ProvedorFalso.chat().willReturn(foraDoAr()));
        groq.stubFor(ProvedorFalso.chat().willReturn(foraDoAr()));
    }

    private void todosDeVolta() {
        gemini.resetAll();
        groq.resetAll();
        ambiente.reiniciarCircuitos();
        gemini.stubFor(ProvedorFalso.chat().willReturn(valida()));
        groq.stubFor(ProvedorFalso.chat().willReturn(valida()));
    }

    private Chamado recarregar(UUID id) {
        return repositorio.findById(id).orElseThrow();
    }

    private int requisicoesAosProvedores() {
        return gemini.findAll(postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO))).size()
                + groq.findAll(postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO))).size();
    }

    @Test
    @DisplayName("todos os provedores fora: a API registra e consulta normalmente, e o chamado fica pendente sem 500")
    void apiContinuaRespondendo() throws Exception {
        todosForaDoAr();

        String corpo = mockMvc.perform(post("/api/chamados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"texto\":\"%s\"}".formatted(TEXTO)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(corpo, "$.id");

        Instant falhouEm = relogio.instant();
        triador.processarProximo();

        mockMvc.perform(get("/api/chamados/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDENTE_TRIAGEM_MANUAL"))
                .andExpect(jsonPath("$.texto").value(TEXTO))
                .andExpect(jsonPath("$.triagem").value(nullValue()))
                .andExpect(jsonPath("$.proximaTentativaAutomatica")
                        .value(falhouEm.plus(Duration.ofMinutes(5)).toString()));
    }

    @Test
    @DisplayName("o worker de chamados novos nao pega pendentes")
    void workerNaoPegaPendentes() {
        todosForaDoAr();
        servico.registrar(TEXTO);
        triador.processarProximo();
        int antes = requisicoesAosProvedores();

        relogio.avancar(Duration.ofDays(1));

        assertThat(triador.processarProximo()).isFalse();
        assertThat(requisicoesAosProvedores()).isEqualTo(antes);
    }

    @Test
    @DisplayName("o revisor nao pega chamados novos, nem pendentes antes da hora")
    void revisorRespeitaAHora() {
        todosForaDoAr();
        servico.registrar("Chamado novo, ainda nao triado.");
        assertThat(triador.processarProximoPendente()).isFalse();

        triador.processarProximo();
        int antes = requisicoesAosProvedores();

        relogio.avancar(Duration.ofMinutes(5).minusSeconds(1));

        assertThat(triador.processarProximoPendente()).isFalse();
        assertThat(requisicoesAosProvedores()).isEqualTo(antes);
    }

    @Test
    @DisplayName("passada a espera, o revisor tenta de novo e, com os provedores de volta, o chamado e triado")
    void revisorTriaQuandoOsProvedoresVoltam() {
        todosForaDoAr();
        UUID id = servico.registrar(TEXTO).getId();
        triador.processarProximo();

        todosDeVolta();
        relogio.avancar(Duration.ofMinutes(5).plusSeconds(1));

        assertThat(triador.processarProximoPendente()).isTrue();

        Chamado chamado = recarregar(id);
        assertThat(chamado.getStatus()).isEqualTo(StatusChamado.TRIADO);
        assertThat(chamado.getProvedor()).isEqualTo("gemini");
        assertThat(chamado.getProximaTentativaEm()).isNull();
    }

    @Test
    @DisplayName("falhas seguidas espacam as revisoes (5min, 30min, 2h) e depois o chamado espera so uma pessoa")
    void revisoesEspacadasAteDesistir() {
        todosForaDoAr();
        UUID id = servico.registrar(TEXTO).getId();

        triador.processarProximo();
        assertThat(esperaAgendada(id)).isEqualTo(Duration.ofMinutes(5));

        relogio.avancar(Duration.ofMinutes(5));
        assertThat(triador.processarProximoPendente()).isTrue();
        assertThat(esperaAgendada(id)).isEqualTo(Duration.ofMinutes(30));

        relogio.avancar(Duration.ofMinutes(30));
        assertThat(triador.processarProximoPendente()).isTrue();
        assertThat(esperaAgendada(id)).isEqualTo(Duration.ofHours(2));

        relogio.avancar(Duration.ofHours(2));
        assertThat(triador.processarProximoPendente()).isTrue();

        Chamado desistido = recarregar(id);
        assertThat(desistido.getStatus()).isEqualTo(StatusChamado.PENDENTE_TRIAGEM_MANUAL);
        assertThat(desistido.getFalhasDeTriagem()).isEqualTo(4);
        assertThat(desistido.getProximaTentativaEm()).isNull();
        assertThat(desistido.getTexto()).isEqualTo(TEXTO);

        // Esgotadas as tentativas automaticas, nem uma semana depois ele e revisitado.
        int antes = requisicoesAosProvedores();
        relogio.avancar(Duration.ofDays(7));
        assertThat(triador.processarProximoPendente()).isFalse();
        assertThat(requisicoesAosProvedores()).isEqualTo(antes);
    }

    @Test
    @DisplayName("o revisor atende primeiro o pendente mais atrasado")
    void maisAtrasadoPrimeiro() {
        todosForaDoAr();
        UUID primeiro = servico.registrar("Primeiro chamado pendente.").getId();
        triador.processarProximo();
        relogio.avancar(Duration.ofMinutes(1));
        servico.registrar("Segundo chamado pendente.");
        triador.processarProximo();

        todosDeVolta();
        relogio.avancar(Duration.ofMinutes(10));
        triador.processarProximoPendente();

        assertThat(recarregar(primeiro).getStatus()).isEqualTo(StatusChamado.TRIADO);
    }

    /** Quanto falta, a partir de agora, para a proxima tentativa automatica do chamado. */
    private Duration esperaAgendada(UUID id) {
        return Duration.between(relogio.instant(), recarregar(id).getProximaTentativaEm());
    }
}
