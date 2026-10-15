package com.joaoalcantara.triagem.chamado;

/** Area responsavel pelo chamado. */
public enum Categoria {
    /** Login, senha, permissao, conta bloqueada. */
    ACESSO,
    /** Cobranca, boleto, pagamento, reembolso, nota fiscal. */
    FINANCEIRO,
    /** Erro, falha, lentidao: algo que deveria funcionar e nao funciona. */
    TECNICO,
    /** Como usar, onde encontrar: nada esta quebrado. */
    DUVIDA,
    OUTROS
}
