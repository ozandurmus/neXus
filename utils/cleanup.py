def cleanup_all(cfg):

    import paramiko
    import os
    from utils.cp_ssh_trust import apply_strict_host_key_policy

    strict_host_key = os.getenv("SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY", "").strip().lower() not in {"", "0", "false", "no", "off", "disabled"}
    ssh = paramiko.SSHClient()
    apply_strict_host_key_policy(ssh, strict_host_key)

    ssh.connect(cfg.mds_ip, **{"username": cfg.auth.principal, "password": cfg.auth.secret})

    commands = [
        "rm -f /home/admin/cp_inventory.sh",
        "rm -f /home/admin/vsx_inventory.sh",
        "rm -f /home/admin/gw_interfaces.csv",
        "rm -f /home/admin/vsx_output.csv"
    ]

    for cmd in commands:
        ssh.exec_command(cmd)

    ssh.close()

    print("Cleanup done")