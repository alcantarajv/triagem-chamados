package com.joaoalcantara.triagem.provedor;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

@Configuration
@EnableConfigurationProperties({PropriedadesDosProvedores.class, PropriedadesDaRepeticao.class,
        PropriedadesDoDisjuntor.class})
public class ConfiguracaoDosProvedores {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracaoDosProvedores.class);

    /**
     * Um circuit breaker por provedor sai deste registro, todos com a mesma
     * configuracao.
     *
     * <p>"N falhas seguidas" no Resilience4j: janela das ultimas N chamadas,
     * minimo de N chamadas para avaliar e limite de 100% de falhas. O circuito
     * abre quando as N ultimas falharam todas — uma resposta boa no meio zera a
     * contagem. Uma taxa menor (50% das ultimas 10, por exemplo) abriria o
     * circuito de um provedor que esta so instavel, e mandaria para o fallback
     * metade dos chamados que ele ainda resolveria.</p>
     *
     * <p>So {@link FalhaNoProvedorException} conta como falha: resposta que nao
     * passou na validacao e um provedor que respondeu, e e outra politica que
     * cuida dela.</p>
     *
     * <p>O relogio e o da aplicacao: nos testes, a janela de tempo aberto passa
     * num instante, sem esperar um minuto de verdade.</p>
     */
    @Bean
    CircuitBreakerRegistry disjuntores(PropriedadesDoDisjuntor propriedades, Clock relogio) {
        CircuitBreakerConfig configuracao = CircuitBreakerConfig.custom()
                .slidingWindow(propriedades.falhasSeguidas(), propriedades.falhasSeguidas(), SlidingWindowType.COUNT_BASED)
                .failureRateThreshold(100)
                .waitDurationInOpenState(propriedades.tempoAberto())
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordException(erro -> erro instanceof FalhaNoProvedorException)
                .clock(relogio)
                .build();
        return CircuitBreakerRegistry.of(configuracao);
    }

    /**
     * Monta a cadeia: cada provedor com nova tentativa por fora, circuit breaker
     * por dentro e o cliente HTTP no centro.
     *
     * <p>Provedor sem chave fica fora da cadeia, com um aviso: incluido, ele
     * gastaria uma requisicao fadada ao 401 em todo chamado, e o circuito dele
     * ficaria abrindo e fechando a toa. Sem provedor nenhum, a aplicacao sobe do
     * mesmo jeito — os chamados continuam sendo recebidos e vao para triagem
     * manual. Recusar subir tiraria do ar tambem o registro de chamados, que nao
     * depende de IA nenhuma.</p>
     */
    @Bean
    ProvedoresDeIa provedoresDeIa(PropriedadesDosProvedores provedores, PropriedadesDaRepeticao repeticao,
                                  CircuitBreakerRegistry disjuntores, Clock relogio) {
        List<ProvedorDeIa> cadeia = new ArrayList<>();
        provedores.emOrdem().forEach((nome, propriedades) -> {
            if (!propriedades.temChave()) {
                log.warn("Provedor {} sem chave configurada: fica fora da cadeia de triagem", nome);
                return;
            }
            ProvedorDeIa cliente = new ProvedorCompativelComOpenAi(nome, propriedades, relogio);
            ProvedorDeIa comDisjuntor = new ProvedorComDisjuntor(cliente, disjuntores.circuitBreaker(nome));
            cadeia.add(new ProvedorComRepeticao(comDisjuntor, repeticao));
        });
        if (cadeia.isEmpty()) {
            log.warn("Nenhum provedor de IA configurado: os chamados serao recebidos e irao para triagem manual");
        } else {
            log.info("Cadeia de triagem: {}", cadeia.stream().map(ProvedorDeIa::nome).toList());
        }
        return new ProvedoresDeIa(cadeia);
    }
}
