package com.joaoalcantara.triagem.provedor;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Provedores de IA configurados, lidos de {@code triagem.provedores.*}. */
@ConfigurationProperties("triagem.provedores")
public record PropriedadesDosProvedores(PropriedadesDoProvedor gemini) {
}
