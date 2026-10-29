-- =====================================================================
--  MySQL 专用索引（H2 dev 模式不需要；正式库请务必执行）
--  执行方式：mysql -uroot -p case_flow < sql/index-mysql.sql
--
--  ⚠ 只在「首次建库」后执行一次；升级时不要再执行。
--     MySQL 的 CREATE INDEX 没有 IF NOT EXISTS，重复执行会报
--     "Duplicate key name"。报错本身不影响任何数据，但会让人误以为升级失败。
--     升级要做的补列由程序启动时（SchemaMigration）自动完成，不需要手工 SQL。
-- =====================================================================
CREATE INDEX idx_emp_parent    ON org_employee (parent_id);
CREATE INDEX idx_emp_no        ON org_employee (employee_no);
CREATE INDEX idx_emp_path      ON org_employee (id_path);
CREATE INDEX idx_case_status   ON case_info (status);
CREATE INDEX idx_case_deadline ON case_info (deadline);
CREATE INDEX idx_case_created  ON case_info (created_at);
CREATE INDEX idx_assignee_case ON case_assignee (case_id, status);
CREATE INDEX idx_assignee_emp  ON case_assignee (employee_id, status);
CREATE INDEX idx_file_case     ON case_file (case_id);
CREATE INDEX idx_suspect_case   ON case_suspect (case_id);
CREATE INDEX idx_log_target    ON operation_log (target_type, target_id, created_at);

-- 待办反馈（2026-10-04）：主查询 todo_id + 时间正序；按案件清理用 case_id
CREATE INDEX idx_todo_feedback_todo ON case_todo_feedback(todo_id, created_at);
CREATE INDEX idx_todo_feedback_case ON case_todo_feedback(case_id);

-- 疑问问答（2026-10-04）：按案件取问答列表
CREATE INDEX idx_question_case ON case_question(case_id, created_at);

-- 工作记录本（2026-10-29）：按人取记录；按案件检索
CREATE INDEX idx_worklog_emp ON work_log(employee_id, created_at);
CREATE INDEX idx_worklog_case ON work_log(case_id);
