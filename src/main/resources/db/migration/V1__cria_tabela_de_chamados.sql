-- Chamados de suporte.
--
-- Nesta etapa o chamado so nasce: grava o texto e fica RECEBIDO. As colunas do
-- resultado da triagem (categoria, prioridade, resumo) chegam com o primeiro
-- provedor, numa migracao propria.

CREATE TABLE chamados (
    -- UUID, e nao BIGINT sequencial: a API nao tem autenticacao, e o id e a unica
    -- coisa entre um visitante e o chamado de outra pessoa. Com numero
    -- sequencial, bastaria somar 1. Gerado pela aplicacao na versao 7, que
    -- comeca pelo instante de criacao: os ids novos vao para o fim do indice,
    -- como num sequencial, em vez de cair em paginas aleatorias.
    id        UUID         PRIMARY KEY,
    texto     TEXT         NOT NULL,
    status    VARCHAR(30)  NOT NULL,
    criado_em TIMESTAMPTZ  NOT NULL,

    -- O mesmo limite da validacao da API, repetido aqui: a API e uma porta de
    -- entrada, o banco e a garantia. Texto sem limite e custo sem limite, porque
    -- o provedor de IA cobra por tamanho.
    CONSTRAINT chamados_texto_tamanho CHECK (char_length(texto) BETWEEN 1 AND 4000),
    CONSTRAINT chamados_status_valido CHECK (status IN (
        'RECEBIDO', 'TRIADO', 'PENDENTE_TRIAGEM_MANUAL'
    ))
);
