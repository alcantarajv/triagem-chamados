package com.joaoalcantara.triagem.custo;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Descobre de que endereco IP veio a requisicao, para o limite por cliente.
 *
 * <p>A API nao tem autenticacao, entao o IP e o que identifica um cliente. Atras
 * de proxies — o Render poe os dele na frente da aplicacao —, o endereco da
 * conexao e o do ultimo proxy, e o do cliente vem num cabecalho.</p>
 *
 * <p>O cuidado e <b>qual</b> endereco usar. No {@code X-Forwarded-For}, cada
 * proxy acrescenta o endereco de quem o chamou no fim da lista, mas o cliente
 * pode mandar o cabecalho ja preenchido com o que quiser. O primeiro da lista —
 * o que quase todo exemplo usa — e justamente o que o cliente controla: trocando-o
 * a cada requisicao, o limite deixaria de valer. Confiavel e so o que os nossos
 * proxies escreveram: contando de tras para frente, tantos enderecos quantos
 * forem os proxies da propria infraestrutura.</p>
 *
 * <p>A alternativa e um cabecalho que a infraestrutura <i>sobrescreve</i>, em vez
 * de acrescentar — como o {@code True-Client-IP} da Cloudflare. Quando
 * {@code cabecalhoDoIp} esta configurado, ele tem precedencia. Qual dos dois
 * caminhos vale depende de como a plataforma de deploy se comporta, e isso se
 * mede, nao se supoe: com o log desta classe em DEBUG, cada requisicao registra
 * o <i>formato</i> dos cabecalhos — quantos enderecos vieram, quais cabecalhos
 * estavam presentes —, nunca os enderecos.</p>
 */
@Component
public class IdentificadorDeCliente {

    private static final Logger log = LoggerFactory.getLogger(IdentificadorDeCliente.class);

    private final int proxiesConfiaveis;
    private final String cabecalhoDoIp;

    public IdentificadorDeCliente(PropriedadesDoCusto propriedades) {
        this.proxiesConfiaveis = propriedades.proxiesConfiaveis();
        this.cabecalhoDoIp = propriedades.cabecalhoDoIp();
    }

    public String enderecoDe(HttpServletRequest requisicao) {
        String encaminhado = requisicao.getHeader("X-Forwarded-For");
        List<String> enderecos = encaminhado == null ? List.of()
                : Arrays.stream(encaminhado.split(",")).map(String::strip).filter(e -> !e.isEmpty()).toList();
        registrarFormato(requisicao, enderecos.size());

        if (cabecalhoDoIp != null && !cabecalhoDoIp.isBlank()) {
            String doCabecalho = requisicao.getHeader(cabecalhoDoIp);
            if (doCabecalho != null && !doCabecalho.isBlank()) {
                return doCabecalho.strip();
            }
        }
        if (proxiesConfiaveis <= 0 || enderecos.size() < proxiesConfiaveis) {
            // Sem proxy configurado, ou menos enderecos do que proxies: a
            // requisicao nao passou pelo caminho esperado, e nada no cabecalho e
            // confiavel.
            return requisicao.getRemoteAddr();
        }
        return enderecos.get(enderecos.size() - proxiesConfiaveis);
    }

    /** Diagnostico sem dado pessoal: so contagens e presenca de cabecalhos. */
    private void registrarFormato(HttpServletRequest requisicao, int enderecosNoForwardedFor) {
        if (log.isDebugEnabled()) {
            log.debug("Cabecalhos de origem: X-Forwarded-For com {} endereco(s); True-Client-IP {}; "
                            + "CF-Connecting-IP {}; X-Real-IP {}",
                    enderecosNoForwardedFor,
                    presente(requisicao, "True-Client-IP"),
                    presente(requisicao, "CF-Connecting-IP"),
                    presente(requisicao, "X-Real-IP"));
        }
    }

    private static String presente(HttpServletRequest requisicao, String cabecalho) {
        return requisicao.getHeader(cabecalho) == null ? "ausente" : "presente";
    }
}
