package com.joaoalcantara.triagem.infra;

import java.time.Clock;
import java.time.ZoneOffset;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Expoe o relogio da aplicacao como bean.
 *
 * <p>Nenhuma regra deve chamar {@code Instant.now()} diretamente. Neste projeto
 * o tempo decide muita coisa — a janela do circuit breaker, a espera do
 * {@code Retry-After}, a virada do orcamento diario —, e regra que depende de
 * tempo so e testavel se o tempo for controlavel. Nos testes este bean vira um
 * relogio que o proprio teste move.</p>
 */
@Configuration
public class RelogioConfig {

    @Bean
    public Clock relogio() {
        return Clock.system(ZoneOffset.UTC);
    }
}
