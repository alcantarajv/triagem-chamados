package com.joaoalcantara.triagem.triagem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.joaoalcantara.triagem.chamado.Classificacao;
import com.joaoalcantara.triagem.provedor.ProvedorDeIa;

/**
 * Pede a classificacao ao provedor e so a aceita depois de validada.
 *
 * <p>Resposta invalida ganha <b>uma</b> nova tentativa, com os motivos da recusa
 * no pedido. Uma, e nao "ate dar certo": o erro de formato costuma ser pontual,
 * e uma correcao com o erro apontado resolve a maioria; se o modelo erra de novo
 * mesmo sabendo o que errou, insistir com ele tende a repetir o erro e gasta
 * cota a cada volta. Depois da segunda recusa, a vez e do proximo provedor
 * (Etapa 6) ou da triagem manual.</p>
 *
 * <p>Essa nova tentativa vale so para resposta <i>invalida</i>. Falha de
 * comunicacao — erro HTTP, tempo esgotado — nao passa por aqui: sai do provedor
 * como {@code FalhaNoProvedorException}, e repetir ou nao e outra politica
 * (Etapa 5). Mandar "sua resposta estava errada" a um provedor que nem
 * respondeu nao faria sentido.</p>
 */
@Component
public class ClassificadorComValidacao {

    private static final Logger log = LoggerFactory.getLogger(ClassificadorComValidacao.class);

    private final ProvedorDeIa provedor;
    private final ValidadorDeClassificacao validador;

    public ClassificadorComValidacao(ProvedorDeIa provedor, ValidadorDeClassificacao validador) {
        this.provedor = provedor;
        this.validador = validador;
    }

    /** Classificacao aceita e o provedor que a produziu. */
    public record TriagemObtida(Classificacao classificacao, String provedor) {
    }

    /**
     * @throws RespostaInvalidaException se a resposta e a correcao forem recusadas
     * @throws com.joaoalcantara.triagem.provedor.FalhaNoProvedorException se o
     *         provedor nao responder
     */
    public TriagemObtida classificar(String texto) {
        String resposta = provedor.classificar(texto);
        ResultadoDaValidacao primeira = validador.validar(resposta);
        if (primeira instanceof ResultadoDaValidacao.Valida valida) {
            return new TriagemObtida(valida.classificacao(), provedor.nome());
        }

        var erros = ((ResultadoDaValidacao.Invalida) primeira).erros();
        log.info("Resposta de {} recusada, pedindo correcao: {}", provedor.nome(), erros);

        String corrigida = provedor.corrigir(texto, resposta, erros);
        return switch (validador.validar(corrigida)) {
            case ResultadoDaValidacao.Valida valida -> new TriagemObtida(valida.classificacao(), provedor.nome());
            case ResultadoDaValidacao.Invalida invalida -> throw new RespostaInvalidaException(provedor.nome(), invalida.erros());
        };
    }
}
