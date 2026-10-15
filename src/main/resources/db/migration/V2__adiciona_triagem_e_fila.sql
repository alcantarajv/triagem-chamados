-- Resultado da triagem e controle da fila do worker.

ALTER TABLE chamados
    ADD COLUMN categoria     VARCHAR(20),
    ADD COLUMN prioridade    VARCHAR(10),
    ADD COLUMN resumo        VARCHAR(120),
    -- Qual provedor classificou. Com um so provedor parece redundante; com a
    -- cadeia de fallback da Etapa 6, e o que diz quem respondeu de fato.
    ADD COLUMN provedor      VARCHAR(30),
    ADD COLUMN triado_em     TIMESTAMPTZ,
    -- Reserva do worker: ate quando este chamado pertence a quem o pegou.
    -- Ver o comentario do indice abaixo.
    ADD COLUMN reservado_ate TIMESTAMPTZ;

ALTER TABLE chamados
    ADD CONSTRAINT chamados_categoria_valida CHECK (categoria IN (
        'ACESSO', 'FINANCEIRO', 'TECNICO', 'DUVIDA', 'OUTROS'
    )),
    ADD CONSTRAINT chamados_prioridade_valida CHECK (prioridade IN (
        'BAIXA', 'MEDIA', 'ALTA', 'CRITICA'
    )),
    -- Chamado TRIADO sem classificacao seria um estado impossivel que a
    -- aplicacao teria de tratar em todo lugar que le um chamado. Melhor que ele
    -- nao possa existir.
    ADD CONSTRAINT chamados_triado_completo CHECK (
        status <> 'TRIADO'
        OR (categoria IS NOT NULL AND prioridade IS NOT NULL AND resumo IS NOT NULL AND triado_em IS NOT NULL)
    );

-- A fila do worker e esta tabela, como no outbox do api-pedidos. O worker
-- procura o chamado RECEBIDO mais antigo com:
--
--     SELECT ... WHERE status = 'RECEBIDO' ORDER BY criado_em LIMIT 1
--     FOR UPDATE SKIP LOCKED
--
-- Indice parcial: so os chamados que esperam triagem entram nele. Os triados,
-- que sao a imensa maioria com o tempo, nao pesam na busca.
CREATE INDEX chamados_fila_de_triagem ON chamados (criado_em) WHERE status = 'RECEBIDO';
