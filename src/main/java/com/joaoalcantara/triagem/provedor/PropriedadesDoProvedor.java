package com.joaoalcantara.triagem.provedor;

import java.time.Duration;
import java.time.ZoneId;

/**
 * Configuracao de um provedor com API compativel com a da OpenAI.
 *
 * @param url                 endereco base da API; nos testes, o do WireMock
 * @param chave               chave de API; vazia quando nao configurada
 * @param modelo              identificador do modelo no provedor
 * @param tempoLimite         tempo maximo de uma chamada
 * @param esforcoDeRaciocinio {@code reasoning_effort} enviado ao modelo, para os
 *                            que raciocinam antes de responder; vazio para nao enviar
 * @param orcamentoDiario     requisicoes permitidas por dia, um pouco abaixo da cota do provedor
 * @param fusoDoOrcamento     fuso em que o provedor vira o dia da cota
 */
public record PropriedadesDoProvedor(String url, String chave, String modelo, Duration tempoLimite,
                                     String esforcoDeRaciocinio, int orcamentoDiario, ZoneId fusoDoOrcamento) {

    public boolean temChave() {
        return chave != null && !chave.isBlank();
    }

    public boolean temEsforcoDeRaciocinio() {
        return esforcoDeRaciocinio != null && !esforcoDeRaciocinio.isBlank();
    }
}
