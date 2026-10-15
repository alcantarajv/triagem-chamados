package com.joaoalcantara.triagem.provedor;

import java.util.LinkedHashMap;
import java.util.SequencedMap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Provedores de IA configurados, lidos de {@code triagem.provedores.*}. */
@ConfigurationProperties("triagem.provedores")
public record PropriedadesDosProvedores(PropriedadesDoProvedor gemini, PropriedadesDoProvedor groq) {

    /**
     * Os provedores na ordem da cadeia de fallback: o primeiro e o principal.
     *
     * <p>Gemini primeiro por ter mais folga de tokens por minuto (cerca de 250
     * mil, contra 8 mil do Groq) — o Groq aguenta poucos chamados por minuto e
     * funciona melhor como reserva do que como caminho de todo dia.</p>
     */
    public SequencedMap<String, PropriedadesDoProvedor> emOrdem() {
        SequencedMap<String, PropriedadesDoProvedor> provedores = new LinkedHashMap<>();
        provedores.put("gemini", gemini);
        provedores.put("groq", groq);
        return provedores;
    }
}
