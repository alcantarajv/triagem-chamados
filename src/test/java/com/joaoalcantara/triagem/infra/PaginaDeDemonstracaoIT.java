package com.joaoalcantara.triagem.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** A pagina de demonstracao na raiz. */
@TesteDeIntegracao
@AutoConfigureMockMvc
class PaginaDeDemonstracaoIT {

    @Autowired
    private MockMvc mockMvc;

    private String pagina() throws Exception {
        return mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a raiz serve a pagina de demonstracao")
    void raizServeAPagina() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
    }

    @Test
    @DisplayName("a pagina avisa para nao colar dados pessoais e traz exemplos ficticios")
    void avisoEExemplos() throws Exception {
        String html = pagina();

        assertThat(html).contains("Não cole dados pessoais");
        assertThat(html).contains("const EXEMPLOS");
    }

    @Test
    @DisplayName("o script nunca insere HTML: o resumo vem do modelo, a partir de um texto que o visitante controla")
    void scriptSemInsercaoDeHtml() throws Exception {
        String html = pagina();
        String script = html.substring(html.indexOf("<script>"), html.indexOf("</script>"));

        assertThat(script)
                .doesNotContain("innerHTML")
                .doesNotContain("outerHTML")
                .doesNotContain("insertAdjacentHTML")
                .doesNotContain("document.write")
                .contains("textContent");
    }
}
