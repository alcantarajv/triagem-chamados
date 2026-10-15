package com.joaoalcantara.triagem.provedor;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({PropriedadesDosProvedores.class, PropriedadesDaRepeticao.class})
public class ConfiguracaoDosProvedores {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracaoDosProvedores.class);

    /**
     * O Gemini, unico provedor por enquanto, ja com a politica de nova tentativa.
     *
     * <p>Sem chave, a aplicacao sobe do mesmo jeito e avisa no log: os chamados
     * continuam sendo recebidos, e a triagem falha ate a chave ser configurada.
     * Recusar subir tiraria do ar tambem o registro de chamados, que nao depende
     * de IA nenhuma.</p>
     */
    @Bean
    ProvedorDeIa gemini(PropriedadesDosProvedores propriedades, PropriedadesDaRepeticao repeticao, Clock relogio) {
        PropriedadesDoProvedor gemini = propriedades.gemini();
        if (!gemini.temChave()) {
            log.warn("GEMINI_API_KEY nao configurada: os chamados serao recebidos, mas a triagem automatica vai falhar");
        }
        return new ProvedorComRepeticao(new ProvedorCompativelComOpenAi("gemini", gemini, relogio), repeticao);
    }
}
