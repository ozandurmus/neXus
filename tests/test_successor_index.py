from scripts import successor_index


def test_committed_index_matches_fresh_render():
    assert successor_index.INDEX.read_text(encoding="utf-8") == successor_index.render()


def test_every_named_status_relation_resolves():
    assert successor_index.unresolved(successor_index.records()) == []
