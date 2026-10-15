package com.joaoalcantara.triagem.provedor;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/**
 * Envolve um provedor com nova tentativa para falhas transitorias.
 *
 * <p>Repetir com criterio, e nao repetir sempre:</p>
 * <ul>
 *   <li><b>So o que e transitorio.</b> Um {@code 400} ou {@code 401} se repete
 *       igual; tentar de novo so gasta tempo. Ver {@link TradutorDeFalhas}.</li>
 *   <li><b>Espera crescente, com variacao aleatoria.</b> Espera fixa martela um
 *       provedor que ja esta sobrecarregado; e sem a variacao, varias instancias
 *       que falharam juntas tentariam de novo juntas, no mesmo instante.</li>
 *   <li><b>O {@code Retry-After} manda.</b> Se o provedor disse quanto esperar,
 *       espera-se isso — tentar antes da hora so gasta cota para receber outro
 *       {@code 429}. E se ele pedir mais que {@code esperaMaxima}, nao se
 *       espera: o worker nao fica parado um minuto com o chamado na mao, e a
 *       falha segue adiante na hora (para o proximo provedor, na Etapa 6).</li>
 * </ul>
 *
 * <p>A espera acontece na thread do worker, que nao atende requisicao de
 * ninguem: segurar o worker alguns segundos nao afeta a API.</p>
 */
public class ProvedorComRepeticao implements ProvedorDeIa {

    private static final Logger log = LoggerFactory.getLogger(ProvedorComRepeticao.class);

    private final ProvedorDeIa provedor;
    private final Retry repeticao;
    private final Duration esperaMaxima;
    private final IntervalFunction esperaCrescente;

    public ProvedorComRepeticao(ProvedorDeIa provedor, PropriedadesDaRepeticao propriedades) {
        this.provedor = provedor;
        this.esperaMaxima = propriedades.esperaMaxima();
        // Dobra a cada falha (1s, 2s, 4s...), com 50% de variacao para cada lado
        // e nunca acima da espera maxima.
        this.esperaCrescente = IntervalFunction.ofExponentialRandomBackoff(
                propriedades.esperaInicial(), 2.0, 0.5, propriedades.esperaMaxima());

        RetryConfig configuracao = RetryConfig.<String>custom()
                .maxAttempts(propriedades.tentativas())
                .retryOnException(this::deveRepetir)
                .intervalBiFunction(this::espera)
                .build();
        this.repeticao = Retry.of(provedor.nome(), configuracao);
        this.repeticao.getEventPublisher().onRetry(evento -> log.info(
                "Nova tentativa {} de {} no provedor {} em {} ms: {}",
                evento.getNumberOfRetryAttempts() + 1, propriedades.tentativas(), provedor.nome(),
                evento.getWaitInterval().toMillis(),
                evento.getLastThrowable() == null ? "" : evento.getLastThrowable().getMessage()));
    }

    @Override
    public String nome() {
        return provedor.nome();
    }

    @Override
    public String classificar(String texto) {
        return comRepeticao(() -> provedor.classificar(texto));
    }

    @Override
    public String corrigir(String texto, String respostaAnterior, List<String> erros) {
        return comRepeticao(() -> provedor.corrigir(texto, respostaAnterior, erros));
    }

    private String comRepeticao(Supplier<String> chamada) {
        return Retry.decorateSupplier(repeticao, chamada).get();
    }

    private boolean deveRepetir(Throwable erro) {
        if (!(erro instanceof FalhaNoProvedorException falha) || !falha.transitoria()) {
            return false;
        }
        return falha.esperaPedida()
                .map(espera -> espera.compareTo(esperaMaxima) <= 0)
                .orElse(true);
    }

    /** O {@code Retry-After}, quando o provedor mandou; senao, a espera crescente. */
    private Long espera(Integer tentativa, Either<Throwable, String> resultado) {
        if (resultado.isLeft() && resultado.getLeft() instanceof FalhaNoProvedorException falha
                && falha.esperaPedida().isPresent()) {
            return falha.esperaPedida().get().toMillis();
        }
        return esperaCrescente.apply(tentativa);
    }
}
