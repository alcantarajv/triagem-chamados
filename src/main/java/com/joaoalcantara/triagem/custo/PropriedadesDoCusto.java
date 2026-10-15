package com.joaoalcantara.triagem.custo;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controle de custo, lido de {@code triagem.custo.*}.
 *
 * @param validadeDoCache           por quanto tempo uma classificacao guardada e reaproveitada
 * @param chamadosPorHora           quantos chamados um mesmo cliente pode registrar por hora
 * @param proxiesConfiaveis         quantos proxies da propria infraestrutura ficam entre o
 *                                  cliente e a aplicacao; 0 quando a conexao chega direto
 * @param cabecalhoDoIp             cabecalho que a infraestrutura sobrescreve com o IP do
 *                                  cliente (como {@code True-Client-IP}); vazio para usar o
 *                                  {@code X-Forwarded-For}
 * @param intervaloDaLimpeza        de quanto em quanto tempo os registros vencidos sao apagados
 */
@ConfigurationProperties("triagem.custo")
public record PropriedadesDoCusto(Duration validadeDoCache, int chamadosPorHora, int proxiesConfiaveis,
                                  String cabecalhoDoIp, Duration intervaloDaLimpeza) {
}
