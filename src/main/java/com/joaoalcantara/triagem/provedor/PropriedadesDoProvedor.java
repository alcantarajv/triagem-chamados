package com.joaoalcantara.triagem.provedor;

import java.time.Duration;

/**
 * Configuracao de um provedor com API compativel com a da OpenAI.
 *
 * @param url         endereco base da API; nos testes, o do WireMock
 * @param chave       chave de API; vazia quando nao configurada
 * @param modelo      identificador do modelo no provedor
 * @param tempoLimite tempo maximo de uma chamada
 */
public record PropriedadesDoProvedor(String url, String chave, String modelo, Duration tempoLimite) {

    public boolean temChave() {
        return chave != null && !chave.isBlank();
    }
}
