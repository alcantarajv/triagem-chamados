package com.joaoalcantara.triagem.chamado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;
import com.joaoalcantara.triagem.infra.AmbienteDeTeste;
import com.joaoalcantara.triagem.infra.TesteDeIntegracao;

/** Registro e consulta de chamados pela API, contra um PostgreSQL de verdade. */
@TesteDeIntegracao
@AutoConfigureMockMvc
class ChamadoIT {

    private static final String TEXTO = "Nao consigo emitir a segunda via do boleto pelo aplicativo.";

    @Autowired
    private AmbienteDeTeste ambiente;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChamadoRepositorio repositorio;

    @BeforeEach
    void limpar() {
        // Tambem zera o limite por cliente: todas as requisicoes deste teste
        // saem do mesmo endereco.
        ambiente.limpar();
    }

    private MvcResult registrar(String corpo) throws Exception {
        return mockMvc.perform(post("/api/chamados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo))
                .andReturn();
    }

    private static String corpoCom(String texto) {
        return """
                {"texto":"%s"}
                """.formatted(texto);
    }

    @Test
    @DisplayName("registra o chamado e responde 202 com o id e o endereco para acompanhar")
    void registraERespondeAccepted() throws Exception {
        mockMvc.perform(post("/api/chamados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoCom(TEXTO)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern("/api/chamados/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("RECEBIDO"))
                .andExpect(jsonPath("$.texto").value(TEXTO))
                .andExpect(jsonPath("$.criadoEm").isNotEmpty());
    }

    @Test
    @DisplayName("o chamado fica gravado como RECEBIDO, a espera da triagem")
    void gravaComoRecebido() throws Exception {
        MvcResult resultado = registrar(corpoCom(TEXTO));
        UUID id = UUID.fromString(JsonPath.read(resultado.getResponse().getContentAsString(), "$.id"));

        Chamado gravado = repositorio.findById(id).orElseThrow();
        assertThat(gravado.getStatus()).isEqualTo(StatusChamado.RECEBIDO);
        assertThat(gravado.getTexto()).isEqualTo(TEXTO);
        // UUID versao 7: comeca pelo instante de criacao, nao e sequencial.
        assertThat(gravado.getId().version()).isEqualTo(7);
    }

    @Test
    @DisplayName("o endereco do Location devolve o chamado registrado")
    void consultaPeloLocation() throws Exception {
        MvcResult resultado = registrar(corpoCom(TEXTO));
        String location = resultado.getResponse().getHeader("Location");
        String id = JsonPath.read(resultado.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("RECEBIDO"))
                .andExpect(jsonPath("$.texto").value(TEXTO));
    }

    @Test
    @DisplayName("chamado inexistente responde 404 em Problem Details")
    void chamadoInexistente() throws Exception {
        mockMvc.perform(get("/api/chamados/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.triagem.dev/erros/chamado-nao-encontrado"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("id que nao e UUID responde 400, e nao 500")
    void idInvalido() throws Exception {
        mockMvc.perform(get("/api/chamados/123"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.triagem.dev/erros/parametro-invalido"));
    }

    @Test
    @DisplayName("texto vazio responde 400 com o campo que falhou")
    void textoVazio() throws Exception {
        long antes = repositorio.count();

        mockMvc.perform(post("/api/chamados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoCom("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.triagem.dev/erros/requisicao-invalida"))
                .andExpect(jsonPath("$.erros").value(hasItem("texto: nao pode ser vazio")));

        assertThat(repositorio.count()).isEqualTo(antes);
    }

    @Test
    @DisplayName("corpo sem o campo texto responde 400")
    void semTexto() throws Exception {
        mockMvc.perform(post("/api/chamados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros").value(hasItem("texto: nao pode ser vazio")));
    }

    @Test
    @DisplayName("texto acima do limite responde 400: texto sem limite e custo sem limite")
    void textoLongoDemais() throws Exception {
        String longo = "a".repeat(Chamado.TAMANHO_MAXIMO_DO_TEXTO + 1);

        mockMvc.perform(post("/api/chamados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoCom(longo)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros").value(hasItem("texto: deve ter no maximo 4000 caracteres")));
    }

    @Test
    @DisplayName("texto exatamente no limite e aceito")
    void textoNoLimite() throws Exception {
        String noLimite = "a".repeat(Chamado.TAMANHO_MAXIMO_DO_TEXTO);

        mockMvc.perform(post("/api/chamados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoCom(noLimite)))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("corpo que nao e JSON responde 400 sem expor a mensagem do parser")
    void corpoIlegivel() throws Exception {
        mockMvc.perform(post("/api/chamados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"texto\": "))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.triagem.dev/erros/corpo-ilegivel"))
                .andExpect(jsonPath("$.detail").value("O corpo da requisicao nao e um JSON valido para esta operacao"));
    }

    @Test
    @DisplayName("o banco recusa texto acima do limite mesmo que a validacao da API seja contornada")
    void bancoGaranteOLimite() {
        Chamado longoDemais = new Chamado("a".repeat(Chamado.TAMANHO_MAXIMO_DO_TEXTO + 1), Instant.now());

        assertThatThrownBy(() -> repositorio.saveAndFlush(longoDemais))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
