-- Nova tentativa automatica para chamados pendentes.
--
-- PENDENTE_TRIAGEM_MANUAL nao e um beco sem saida: um job revisita o chamado
-- mais tarde, com espera crescente entre uma rodada e outra. Esgotadas as
-- rodadas, proxima_tentativa_em fica nula e o chamado espera so uma pessoa.

ALTER TABLE chamados
    -- Quantas rodadas de triagem automatica ja falharam para este chamado. Cada
    -- rodada e a cadeia inteira: todos os provedores, com as novas tentativas e
    -- a correcao de resposta invalida de cada um.
    ADD COLUMN falhas_de_triagem    INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN proxima_tentativa_em TIMESTAMPTZ;

ALTER TABLE chamados
    ADD CONSTRAINT chamados_falhas_nao_negativas CHECK (falhas_de_triagem >= 0),
    -- Proxima tentativa automatica so faz sentido para quem esta pendente.
    ADD CONSTRAINT chamados_proxima_tentativa_so_de_pendente CHECK (
        proxima_tentativa_em IS NULL OR status = 'PENDENTE_TRIAGEM_MANUAL'
    );

-- Os pendentes de antes desta migracao nasceram sem agendamento. Em vez de
-- deixa-los esquecidos, cada um ganha uma revisao logo na primeira rodada do job.
UPDATE chamados
SET falhas_de_triagem = 1,
    proxima_tentativa_em = now()
WHERE status = 'PENDENTE_TRIAGEM_MANUAL';

-- A fila do revisor: so os pendentes que ainda tem tentativa agendada.
CREATE INDEX chamados_fila_de_revisao ON chamados (proxima_tentativa_em)
    WHERE status = 'PENDENTE_TRIAGEM_MANUAL' AND proxima_tentativa_em IS NOT NULL;
