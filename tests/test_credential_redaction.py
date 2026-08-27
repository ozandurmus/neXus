from utils import logger


def test_logger_redacts_registered_secret_and_hashes_principal(monkeypatch, tmp_path):
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(logger, "LOG_FILE", "logs/test.log")
    logger._SENSITIVE_VALUES.clear()

    principal = "operator.example"
    secret = "DoNotPersistThis!"
    fingerprint = logger.user_fingerprint(principal)

    logger.register_sensitive_value(principal, f"[USER:{fingerprint}]")
    logger.register_sensitive_value(secret, "[AUTH_SECRET:REDACTED]")
    logger.info(f"login principal={principal} auth_secret={secret}")

    text = (tmp_path / "logs" / "test.log").read_text(encoding="utf-8")
    assert principal not in text
    assert secret not in text
    assert f"[USER:{fingerprint}]" in text
    assert "[AUTH_SECRET:REDACTED]" in text
