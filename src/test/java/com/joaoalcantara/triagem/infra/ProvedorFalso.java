package com.joaoalcantara.triagem.infra;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import java.util.Map;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;

import tools.jackson.databind.json.JsonMapper;

/**
 * Um Gemini e um Groq de mentira, servindo HTTP de verdade.
 *
 * <p>Cada um sobe numa porta livre e registra a propria URL no lugar da do
 * provedor real, para que o adaptador aponte para ele sem saber que e um duble.
 * O que fica exercitado e o caminho inteiro: o SDK monta a requisicao, o
 * WireMock responde no formato da API de chat da OpenAI, e o Spring AI converte
 * a resposta.</p>
 *
 * <p>Dois servidores separados, e nao um so com caminhos diferentes: o teste do
 * circuit breaker precisa contar as requisicoes que chegaram ao principal e ao
 * fallback de forma independente.</p>
 *
 * <p>A alternativa — um {@code ProvedorDeIa} falso em memoria — seria mais
 * rapida, mas nao provaria nada sobre o que de fato vai pela rede: cabecalho de
 * autenticacao, esquema JSON, quantas requisicoes foram feitas. E esse ultimo
 * numero que sustenta os testes de nova tentativa, circuit breaker e cache.</p>
 *
 * <p>Um provedor sem resposta configurada devolve o 404 padrao do WireMock — uma
 * falha definitiva. Assim, um teste que so prepara o Gemini ve o Groq falhar na
 * hora, sem precisar dizer nada sobre ele.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class ProvedorFalso {

    public static final String CHAVE = "chave-de-teste";
    public static final String MODELO = "modelo-de-teste";
    public static final String MODELO_GROQ = "modelo-groq-de-teste";
    public static final String CAMINHO = "/chat/completions";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean(destroyMethod = "stop")
    WireMockServer gemini() {
        return servidorEmPortaLivre();
    }

    @Bean(destroyMethod = "stop")
    WireMockServer groq() {
        return servidorEmPortaLivre();
    }

    @Bean
    DynamicPropertyRegistrar enderecosDosProvedoresFalsos(WireMockServer gemini, WireMockServer groq) {
        return registro -> {
            registro.add("triagem.provedores.gemini.url", gemini::baseUrl);
            registro.add("triagem.provedores.gemini.chave", () -> CHAVE);
            registro.add("triagem.provedores.gemini.modelo", () -> MODELO);
            registro.add("triagem.provedores.groq.url", groq::baseUrl);
            registro.add("triagem.provedores.groq.chave", () -> CHAVE);
            registro.add("triagem.provedores.groq.modelo", () -> MODELO_GROQ);
        };
    }

    private static WireMockServer servidorEmPortaLivre() {
        // dynamicPort: uma porta ocupada por outro processo nao derruba a suite.
        WireMockServer servidor = new WireMockServer(options().dynamicPort());
        servidor.start();
        return servidor;
    }

    /** Requisicao de chat, como o adaptador a envia. */
    public static MappingBuilder chat() {
        return post(urlPathEqualTo(CAMINHO));
    }

    /** Resposta de sucesso cujo texto e {@code conteudo}. */
    public static ResponseDefinitionBuilder responde(String conteudo) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(respostaDeChat(conteudo));
    }

    /** Resposta de sucesso com uma classificacao em JSON, como o modelo a devolveria. */
    public static ResponseDefinitionBuilder classifica(String categoria, String prioridade, String resumo) {
        return responde(JSON.writeValueAsString(Map.of(
                "categoria", categoria,
                "prioridade", prioridade,
                "resumo", resumo)));
    }

    /** Corpo de uma chat completion da API da OpenAI, com os campos que o SDK exige. */
    private static String respostaDeChat(String conteudo) {
        return JSON.writeValueAsString(Map.of(
                "id", "chatcmpl-teste",
                "object", "chat.completion",
                "created", 1_760_000_000,
                "model", MODELO,
                "choices", java.util.List.of(Map.of(
                        "index", 0,
                        "finish_reason", "stop",
                        "message", Map.of("role", "assistant", "content", conteudo))),
                "usage", Map.of("prompt_tokens", 120, "completion_tokens", 30, "total_tokens", 150)));
    }
}
