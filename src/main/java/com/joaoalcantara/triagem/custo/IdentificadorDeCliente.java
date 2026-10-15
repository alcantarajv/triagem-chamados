package com.joaoalcantara.triagem.custo;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Descobre de que endereco IP veio a requisicao, para o limite por cliente.
 *
 * <p>A API nao tem autenticacao, entao o IP e o que identifica um cliente. Atras
 * de um proxy — o Render poe um na frente da aplicacao —, o endereco da conexao
 * e o do proxy, e o do cliente vem no cabecalho {@code X-Forwarded-For}.</p>
 *
 * <p>O cuidado e <b>qual</b> endereco do cabecalho usar. Cada proxy acrescenta o
 * endereco de quem o chamou no fim da lista, mas o cliente pode mandar o
 * cabecalho ja preenchido com o que quiser. O primeiro da lista — o que quase
 * todo exemplo usa — e justamente o que o cliente controla: trocando-o a cada
 * requisicao, o limite deixaria de valer. Confiavel e so o que os nossos
 * proxies escreveram: contando de tras para frente, tantos enderecos quantos
 * forem os proxies da propria infraestrutura.</p>
 */
@Component
public class IdentificadorDeCliente {

    private final int proxiesConfiaveis;

    public IdentificadorDeCliente(PropriedadesDoCusto propriedades) {
        this.proxiesConfiaveis = propriedades.proxiesConfiaveis();
    }

    public String enderecoDe(HttpServletRequest requisicao) {
        if (proxiesConfiaveis <= 0) {
            return requisicao.getRemoteAddr();
        }
        String cabecalho = requisicao.getHeader("X-Forwarded-For");
        if (cabecalho == null || cabecalho.isBlank()) {
            return requisicao.getRemoteAddr();
        }
        List<String> enderecos = Arrays.stream(cabecalho.split(",")).map(String::strip)
                .filter(endereco -> !endereco.isEmpty()).toList();
        if (enderecos.size() < proxiesConfiaveis) {
            // Menos enderecos do que proxies: a requisicao nao passou pelo caminho
            // esperado, e nada no cabecalho e confiavel.
            return requisicao.getRemoteAddr();
        }
        return enderecos.get(enderecos.size() - proxiesConfiaveis);
    }
}
