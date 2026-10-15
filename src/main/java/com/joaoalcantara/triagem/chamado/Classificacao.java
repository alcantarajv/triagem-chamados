package com.joaoalcantara.triagem.chamado;

/**
 * O que a triagem produz: categoria, prioridade e um resumo de uma linha.
 *
 * <p>E tambem o formato que o modelo de IA e instruido a devolver — o esquema
 * JSON enviado ao provedor e gerado a partir deste record. Os campos sao
 * {@code enum} de proposito: o esquema lista os valores aceitos, e uma
 * categoria inventada nao vira texto livre no banco.</p>
 */
public record Classificacao(Categoria categoria, Prioridade prioridade, String resumo) {

    /** Tamanho maximo do resumo, o mesmo da coluna no banco. */
    public static final int TAMANHO_MAXIMO_DO_RESUMO = 120;
}
