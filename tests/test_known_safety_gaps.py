import os
from pathlib import Path

import pytest


def test_logger_can_write_on_fresh_checkout_when_initialized(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)

    import utils.logger as logger

    logger.init_logger(debug=True, log_to_file=True)
    logger.info("baseline logger test")

    assert (tmp_path / "logs").is_dir()


def test_logger_without_explicit_init_should_not_fail_on_fresh_checkout(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)

    import utils.logger as logger

    # Force a fresh log path in the temporary working directory.
    monkeypatch.setattr(logger, "LOG_FILE", os.fspath(Path("logs") / "run_test.log"))
    logger.info("should be safe")
