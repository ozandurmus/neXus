from utils.runtime_auth import RuntimeAuth


class Config:
    """Canonical process configuration for SecurityExpert runtime execution."""

    def __init__(self, principal, secret, mds_ip=None, panorama_ip=None, runtime_paths=None):
        self.auth = RuntimeAuth(principal=principal, secret=secret)
        self.runtime_paths = runtime_paths

        # Debug controls remain local application behavior.
        self.debug = True
        self.log_to_file = True

        # Environment-specific management endpoints are runtime inputs.
        # No production endpoint is embedded in repository source.
        self.panorama_url = panorama_ip
        self.panorama_ip = panorama_ip
        self.mds_ip = mds_ip
        self.cp_mds = self.mds_ip
        self.smc_ip = self.mds_ip
        self.ssh_timeout = 30

    def __repr__(self):
        return "Config(auth=<protected>, endpoints=<runtime>)"

    def clear_credentials(self):
        # Python strings cannot be reliably zeroized in memory, but replacing
        # the auth object drops Config's references after the run.
        self.auth = RuntimeAuth()
