"""Exercise the real aggregate() tail with synthetic retained metadata and APK bytes."""
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import unittest
from unittest.mock import patch

TESTS=Path(__file__).resolve().parent
SCRIPTS=TESTS.parent

def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value

ROLE=load('retained_role_api_fixtures',TESTS/'test_retained_sentinel_role.py')
BASE=load('baseline_delivery_api_fixtures',TESTS/'test_delivery_sbom.py')


class RetainedAggregateAPITest(unittest.TestCase):
    def test_actual_aggregate_returns_truthful_selected_union_hash_without_rewriting_original_identity(self):
        original=ROLE.RetainedSentinelRoleTest();original.setUp();self.addCleanup(original.doCleanups)
        delivery=BASE.DeliverySbomTest();delivery.setUp();self.addCleanup(delivery.doCleanups)
        delivery.revision=ROLE.M.CORE
        (delivery.root/'config/foxcore-revision.txt').write_text(ROLE.M.CORE+'\n')
        shutil.copy2(original.root/'config/sentinel-retained-authority.json',delivery.root/'config/sentinel-retained-authority.json')
        shutil.copy2(SCRIPTS/'retained-sentinel-role.py',delivery.root/'scripts/retained-sentinel-role.py')
        profile={'schema':1,'androidDeliveryProfile':'android64','abis':ROLE.M.ABIS,'targets':ROLE.M.TARGETS}
        (delivery.root/'config/foxcore-android-delivery.json').write_text(json.dumps(profile))
        delivery.inventory['MANIFEST.json']=json.dumps({'foxcoreRevision':ROLE.M.CORE,**profile}).encode()
        delivery.inventory[BASE.SENTINEL_REVISION]=(ROLE.M.EA+'\n').encode()
        del delivery.inventory[BASE.SENTINEL_BOM]
        delivery.inventory.update(original.notices)
        for name,body in list(delivery.libraries.items()):
            delivery.libraries[name.replace('arm64-v8a','x86_64')]=body
        delivery.libraries.update(original.libraries)
        delivery.write_inputs()
        core=json.loads(delivery.inventory['foxcore/foxcore-aarch64-linux-android.cdx.json'])
        coverage_raw=ROLE.M.encoded(original.current_coverage)
        # Current Core graph/native validation has its own qualified fixtures; this test isolates
        # the retained branch and still executes the real aggregate API through its final metadata.
        with patch.object(BASE.MODULE,'android64_core_union',return_value=(copy.deepcopy(core),coverage_raw)):
            result=delivery.aggregate()
        expected,_=original.packaged()
        props={row['name']:row['value']for row in result['metadata']['properties']}
        self.assertEqual(ROLE.M.sha(ROLE.M.encoded(expected)),props['foxhole:sentinelSelectedUnionSha256'])
        self.assertNotIn('foxhole:sentinelBomSha256',props)
        sentinel=next(row for row in result['components']if row['bom-ref']=='sentinel:root')
        self.assertIn(ROLE.M.EA,sentinel['purl'])
        self.assertNotIn(ROLE.M.CORE,sentinel['purl'])
        self.assertEqual(ROLE.M.EA,props['foxhole:sentinelRevision'])


if __name__=='__main__':unittest.main()
