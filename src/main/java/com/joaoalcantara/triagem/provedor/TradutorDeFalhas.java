package com.joaoalcantara.triagem.provedor;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.openai.core.ObjectMappers;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;

/**
 * Traduz as excecoes do SDK da OpenAI para {@link FalhaNoProvedorException},
 * separando o que vale repetir do que nao vale.
 *
 * <p>Transitorio: tempo esgotado, conexao perdida, {@code 5xx}, {@code 408} e
 * {@code 429}. O problema esta no provedor ou no caminho ate ele, e pode passar.
 * Definitivo: {@code 400}, {@code 401}, {@code 403}, {@code 404}, {@code 422}.
 * A requisicao foi recusada pelo que ela e — chave errada, modelo inexistente,
 * corpo invalido — e repeti-la so gasta tempo e cota para receber a mesma
 * recusa.</p>
 *
 * <p>Quanto esperar vem do cabecalho {@code Retry-After} ou, na falta dele, do
 * {@code retryDelay} no corpo, que e onde o Gemini informa.</p>
 *
 * <p>So este pacote conhece o SDK: o resto do sistema ve apenas a traducao.</p>
 */
final class TradutorDeFalhas {

    private TradutorDeFalhas() {
    }

    static FalhaNoProvedorException traduzir(String provedor, RuntimeException erro, Clock relogio) {
        OpenAIServiceException servico = procurar(erro, OpenAIServiceException.class);
        if (servico != null) {
            int status = servico.statusCode();
            String descricao = "HTTP " + status;
            if (status == 429 || status == 408 || status >= 500) {
                Duration espera = esperaPedida(servico.headers().values("Retry-After"), relogio.instant())
                        .or(() -> esperaNoCorpo(corpoComoJson(servico)))
                        .orElse(null);
                if (espera != null) {
                    descricao += " (pediu para esperar %ds)".formatted(espera.toSeconds());
                }
                return FalhaNoProvedorException.transitoria(provedor, descricao, espera, erro);
            }
            return FalhaNoProvedorException.definitiva(provedor, descricao, erro);
        }
        if (procurar(erro, OpenAIIoException.class) != null
                || procurar(erro, OpenAIRetryableException.class) != null
                || procurar(erro, IOException.class) != null) {
            return FalhaNoProvedorException.transitoria(provedor,
                    "sem resposta (" + causaRaiz(erro).getClass().getSimpleName() + ")", null, erro);
        }
        return FalhaNoProvedorException.definitiva(provedor, erro.getClass().getSimpleName(), erro);
    }

    /**
     * Le o {@code Retry-After}, que a RFC 9110 permite em dois formatos: segundos
     * ({@code 30}) ou data HTTP ({@code Wed, 21 Oct 2026 07:28:00 GMT}).
     * Valor que nao se entende e ignorado — a espera volta a ser a da politica,
     * em vez de a falha virar outra falha.
     */
    static Optional<Duration> esperaPedida(List<String> valores, Instant agora) {
        if (valores == null || valores.isEmpty() || valores.getFirst() == null) {
            return Optional.empty();
        }
        String valor = valores.getFirst().strip();
        try {
            double segundos = Double.parseDouble(valor);
            if (segundos < 0 || Double.isNaN(segundos) || Double.isInfinite(segundos)) {
                return Optional.empty();
            }
            return Optional.of(Duration.ofMillis(Math.round(segundos * 1000)));
        } catch (NumberFormatException naoESegundos) {
            try {
                Instant quando = ZonedDateTime.parse(valor, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration ate = Duration.between(agora, quando);
                return Optional.of(ate.isNegative() ? Duration.ZERO : ate);
            } catch (DateTimeParseException naoEData) {
                return Optional.empty();
            }
        }
    }

    /**
     * O Gemini nao manda {@code Retry-After}: o 429 dele chega sem esse cabecalho,
     * com a espera no corpo, no formato de erro das APIs do Google
     * ({@code google.rpc.RetryInfo}):
     *
     * <pre>
     * [{"error": {"code": 429, "status": "RESOURCE_EXHAUSTED", "details": [
     *     ..., {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "26s"}]}}]
     * </pre>
     *
     * <p>Sem esta leitura, o 429 do Gemini cairia na espera crescente: tentar de
     * novo em 1s e 2s quando ele pediu 26s, e receber mais dois 429. Busca por
     * expressao regular no JSON serializado, e nao navegando a estrutura: o
     * Gemini embrulha o erro num array, outro provedor talvez nao, e o campo
     * {@code retryDelay} e o mesmo nos dois casos.</p>
     */
    static Optional<Duration> esperaNoCorpo(String corpoJson) {
        if (corpoJson == null) {
            return Optional.empty();
        }
        Matcher encontrado = RETRY_DELAY.matcher(corpoJson);
        if (!encontrado.find()) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofMillis(Math.round(Double.parseDouble(encontrado.group(1)) * 1000)));
    }

    /** Duracao no formato JSON do protobuf: segundos com fracao opcional e sufixo "s". */
    private static final Pattern RETRY_DELAY = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"");

    private static String corpoComoJson(OpenAIServiceException servico) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(servico.body());
        } catch (Exception corpoIlegivel) {
            return null;
        }
    }

    private static <T extends Throwable> T procurar(Throwable erro, Class<T> tipo) {
        for (Throwable atual = erro; atual != null; atual = atual.getCause()) {
            if (tipo.isInstance(atual)) {
                return tipo.cast(atual);
            }
            if (atual.getCause() == atual) {
                break;
            }
        }
        return null;
    }

    private static Throwable causaRaiz(Throwable erro) {
        Throwable atual = erro;
        while (atual.getCause() != null && atual.getCause() != atual) {
            atual = atual.getCause();
        }
        return atual;
    }
}
