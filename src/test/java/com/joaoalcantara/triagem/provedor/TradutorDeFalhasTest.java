package com.joaoalcantara.triagem.provedor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonField;
import com.openai.core.JsonValue;
import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;

class TradutorDeFalhasTest {

    private static final Instant AGORA = Instant.parse("2026-10-15T12:00:00Z");
    private static final Clock RELOGIO = Clock.fixed(AGORA, ZoneOffset.UTC);

    @Test
    @DisplayName("Retry-After em segundos")
    void retryAfterEmSegundos() {
        assertThat(TradutorDeFalhas.esperaPedida(List.of("30"), AGORA)).contains(Duration.ofSeconds(30));
        assertThat(TradutorDeFalhas.esperaPedida(List.of(" 2.5 "), AGORA)).contains(Duration.ofMillis(2500));
    }

    @Test
    @DisplayName("Retry-After como data HTTP vira o tempo que falta ate ela")
    void retryAfterComoData() {
        assertThat(TradutorDeFalhas.esperaPedida(List.of("Thu, 15 Oct 2026 12:00:45 GMT"), AGORA))
                .contains(Duration.ofSeconds(45));
    }

    @Test
    @DisplayName("Retry-After com data no passado nao pede espera nenhuma")
    void retryAfterNoPassado() {
        assertThat(TradutorDeFalhas.esperaPedida(List.of("Thu, 15 Oct 2026 11:00:00 GMT"), AGORA))
                .contains(Duration.ZERO);
    }

    @Test
    @DisplayName("Retry-After ausente ou ilegivel e ignorado, em vez de virar outra falha")
    void retryAfterIlegivel() {
        assertThat(TradutorDeFalhas.esperaPedida(List.of(), AGORA)).isEmpty();
        assertThat(TradutorDeFalhas.esperaPedida(List.of("amanha cedo"), AGORA)).isEmpty();
        assertThat(TradutorDeFalhas.esperaPedida(List.of("-5"), AGORA)).isEmpty();
        assertThat(TradutorDeFalhas.esperaPedida(List.of("NaN"), AGORA)).isEmpty();
    }

    @Test
    @DisplayName("retryDelay do corpo, no formato do Google, com e sem fracao")
    void retryDelayNoCorpo() {
        String gemini = """
                [{"error":{"code":429,"details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"26.787526249s"}]}}]
                """;

        assertThat(TradutorDeFalhas.esperaNoCorpo(gemini)).contains(Duration.ofMillis(26788));
        assertThat(TradutorDeFalhas.esperaNoCorpo("{\"retryDelay\": \"2s\"}")).contains(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("corpo sem retryDelay nao pede espera")
    void corpoSemRetryDelay() {
        assertThat(TradutorDeFalhas.esperaNoCorpo("{\"error\":{\"code\":503}}")).isEmpty();
        assertThat(TradutorDeFalhas.esperaNoCorpo(null)).isEmpty();
    }

    /** Corpo do 429 real do Gemini, capturado em 2026-10-15 (trechos longos encurtados). */
    private static final String CORPO_DO_429_DO_GEMINI = """
            [{"error": {"code": 429,
              "message": "You exceeded your current quota... Please retry in 26.787526249s.",
              "status": "RESOURCE_EXHAUSTED",
              "details": [
                {"@type": "type.googleapis.com/google.rpc.Help", "links": [{"url": "https://ai.google.dev/gemini-api/docs/rate-limits"}]},
                {"@type": "type.googleapis.com/google.rpc.QuotaFailure", "violations": [{
                  "quotaMetric": "generativelanguage.googleapis.com/generate_content_free_tier_requests",
                  "quotaId": "GenerateRequestsPerMinutePerProjectPerModel-FreeTier", "quotaValue": "15"}]},
                {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "26s"}]}}]
            """;

    @Test
    @DisplayName("429 do Gemini pelo SDK: sem Retry-After, a espera sai do retryDelay do corpo")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void rateLimitDoGeminiPeloSdk() throws Exception {
        RateLimitException erro = RateLimitException.builder()
                .headers(Headers.builder().build())
                .error((JsonField) JsonValue.fromJsonNode(new ObjectMapper().readTree(CORPO_DO_429_DO_GEMINI)))
                .build();

        FalhaNoProvedorException falha = TradutorDeFalhas.traduzir("gemini", erro, RELOGIO);

        assertThat(falha.transitoria()).isTrue();
        assertThat(falha.esperaPedida()).contains(Duration.ofSeconds(26));
        assertThat(falha.getMessage()).isEqualTo("Falha no provedor gemini: HTTP 429 (pediu para esperar 26s)");
    }

    @Test
    @DisplayName("o cabecalho Retry-After tem precedencia sobre o corpo")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void cabecalhoTemPrecedencia() throws Exception {
        RateLimitException erro = RateLimitException.builder()
                .headers(Headers.builder().put("Retry-After", "3").build())
                .error((JsonField) JsonValue.fromJsonNode(new ObjectMapper().readTree(CORPO_DO_429_DO_GEMINI)))
                .build();

        assertThat(TradutorDeFalhas.traduzir("groq", erro, RELOGIO).esperaPedida()).contains(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("503 e transitorio; 400 e definitivo")
    void statusPeloSdk() {
        InternalServerException indisponivel = InternalServerException.builder()
                .statusCode(503).headers(Headers.builder().build()).build();
        BadRequestException invalida = BadRequestException.builder().headers(Headers.builder().build()).build();

        assertThat(TradutorDeFalhas.traduzir("gemini", indisponivel, RELOGIO).transitoria()).isTrue();
        assertThat(TradutorDeFalhas.traduzir("gemini", invalida, RELOGIO).transitoria()).isFalse();
    }

    @Test
    @DisplayName("falha de rede ou tempo esgotado e transitoria")
    void falhaDeRedeETransitoria() {
        FalhaNoProvedorException timeout = TradutorDeFalhas.traduzir("gemini",
                new OpenAIIoException("Request failed", new SocketTimeoutException("timeout")), RELOGIO);
        FalhaNoProvedorException io = TradutorDeFalhas.traduzir("gemini",
                new UncheckedIOException(new java.io.IOException("connection reset")), RELOGIO);

        assertThat(timeout.transitoria()).isTrue();
        assertThat(timeout.getMessage()).contains("sem resposta (SocketTimeoutException)");
        assertThat(io.transitoria()).isTrue();
    }

    @Test
    @DisplayName("erro desconhecido e definitivo: na duvida, nao se gasta cota repetindo")
    void desconhecidoEDefinitivo() {
        FalhaNoProvedorException falha = TradutorDeFalhas.traduzir("gemini", new IllegalStateException("?"), RELOGIO);

        assertThat(falha.transitoria()).isFalse();
        assertThat(falha.provedor()).isEqualTo("gemini");
    }
}
