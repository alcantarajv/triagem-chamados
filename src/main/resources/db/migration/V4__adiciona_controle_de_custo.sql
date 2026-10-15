-- Controle de custo: cache da classificacao, limite por cliente e orcamento
-- diario de requisicoes a cada provedor. As tres tabelas vivem no banco, e nao
-- em memoria, pelo mesmo motivo da fila: com mais de uma instancia, ou depois de
-- um reinicio, um contador em memoria contaria so a sua parte.

-- Cache: o mesmo texto nao gera outra chamada ao provedor.
--
-- A chave e o SHA-256 do texto normalizado; o texto em si nao fica aqui. Uma
-- copia do chamado a mais seria uma copia a mais de dado possivelmente pessoal,
-- e o hash basta para reconhecer o mesmo texto.
CREATE TABLE classificacoes_em_cache (
    chave      CHAR(64)     PRIMARY KEY,
    categoria  VARCHAR(20)  NOT NULL,
    prioridade VARCHAR(10)  NOT NULL,
    resumo     VARCHAR(120) NOT NULL,
    -- Quem classificou originalmente: o chamado que reaproveita o resultado
    -- continua dizendo de onde ele veio.
    provedor   VARCHAR(30)  NOT NULL,
    criado_em  TIMESTAMPTZ  NOT NULL,
    expira_em  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX classificacoes_em_cache_por_expiracao ON classificacoes_em_cache (expira_em);

ALTER TABLE chamados
    ADD COLUMN veio_do_cache BOOLEAN NOT NULL DEFAULT false;

-- Limite de chamados por cliente, em janelas fixas de uma hora.
--
-- O cliente e o hash do endereco IP: o suficiente para contar, sem guardar o
-- endereco. As janelas encerradas sao apagadas pela limpeza periodica, entao
-- nem o hash fica mais que algumas horas.
CREATE TABLE uso_por_cliente (
    cliente  CHAR(64)    NOT NULL,
    janela   TIMESTAMPTZ NOT NULL,
    chamados INTEGER     NOT NULL,
    PRIMARY KEY (cliente, janela)
);

-- Orcamento diario de requisicoes a cada provedor. O dia e o do fuso em que o
-- provedor conta a cota: o do Gemini vira a meia-noite do Pacifico.
CREATE TABLE uso_diario_dos_provedores (
    provedor    VARCHAR(30) NOT NULL,
    dia         DATE        NOT NULL,
    requisicoes INTEGER     NOT NULL,
    PRIMARY KEY (provedor, dia)
);
