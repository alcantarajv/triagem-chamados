package com.joaoalcantara.triagem.custo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.joaoalcantara.triagem.chamado.ChamadoRepositorio;
import com.joaoalcantara.triagem.infra.AmbienteDeTeste;
import com.joaoalcantara.triagem.infra.RelogioAjustavel.ClockMovel;
import com.joaoalcantara.triagem.infra.TesteDeIntegracao;

/** Limite de chamados por cliente: 10 por hora por endereco IP. */
@TesteDeIntegracao
@AutoConfigureMockMvc
class LimiteDeChamadosIT {

    private static final String CLIENTE = "203.0.113.7";
    private static final String OUTRO_CLIENTE = "198.51.100.23";

    @Autowired
    private AmbienteDeTeste ambiente;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChamadoRepositorio repositorio;

    @Autowired
    private PropriedadesDoCusto custo;

    @Autowired
    private ClockMovel relogio;

    @BeforeEach
    void limpar() {
        ambiente.limpar();
        // Comeca no inicio de uma janela, para o teste nao cair na virada da hora.
        relogio.avancar(Duration.between(relogio.instant(),
                relogio.instant().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1))));
    }

    private ResultActions registrar(String endereco, String corpo) throws Exception {
        return mockMvc.perform(post("/api/chamados")
                .with(requisicao -> {
                    requisicao.setRemoteAddr(endereco);
                    return requisicao;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo));
    }

    private ResultActions registrar(String endereco) throws Exception {
        return registrar(endereco, "{\"texto\":\"Nao consigo gerar a nota fiscal.\"}");
    }

    private void usarOLimiteInteiro(String endereco) throws Exception {
        for (int i = 0; i < custo.chamadosPorHora(); i++) {
            registrar(endereco).andExpect(status().isAccepted());
        }
    }

    @Test
    @DisplayName("passado o limite da hora: 429 em Problem Details, com Retry-After, e o chamado nao e gravado")
    void limiteExcedido() throws Exception {
        usarOLimiteInteiro(CLIENTE);

        registrar(CLIENTE)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.triagem.dev/erros/limite-de-chamados"))
                // A janela comecou agora: falta uma hora inteira para virar.
                .andExpect(header().string("Retry-After", "3600"));

        assertThat(repositorio.count()).isEqualTo(custo.chamadosPorHora());
    }

    @Test
    @DisplayName("o limite e por cliente: outro endereco continua registrando")
    void outroClienteNaoEAfetado() throws Exception {
        usarOLimiteInteiro(CLIENTE);

        registrar(OUTRO_CLIENTE).andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("na hora seguinte, o mesmo cliente volta a registrar")
    void janelaSeguinte() throws Exception {
        usarOLimiteInteiro(CLIENTE);
        registrar(CLIENTE).andExpect(status().isTooManyRequests());

        relogio.avancar(Duration.ofHours(1));

        registrar(CLIENTE).andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("Retry-After diz quanto falta para a janela virar")
    void retryAfterAcompanhaAJanela() throws Exception {
        usarOLimiteInteiro(CLIENTE);
        relogio.avancar(Duration.ofMinutes(45));

        registrar(CLIENTE)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"));
    }

    @Test
    @DisplayName("corpo invalido e recusado antes de gastar o limite")
    void invalidoNaoGastaOLimite() throws Exception {
        for (int i = 0; i < custo.chamadosPorHora() * 2; i++) {
            registrar(CLIENTE, "{\"texto\":\"\"}").andExpect(status().isBadRequest());
        }

        registrar(CLIENTE).andExpect(status().isAccepted());
    }
}
