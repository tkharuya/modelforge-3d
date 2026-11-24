#!/usr/bin/env python3
"""Small reproducible build, also works on a JRE using the Eclipse Java compiler.
Requires Android platform 35, build-tools 35.0.0, ECJ 3.39.0 and Java 17+.
The normal Android Studio/Gradle build is also supported.
"""
import argparse, pathlib, subprocess, os, zipfile, shutil
p=argparse.ArgumentParser();p.add_argument('--platform',required=True);p.add_argument('--tools',required=True);p.add_argument('--ecj',required=True);p.add_argument('--keystore',required=True);p.add_argument('--password-file',required=True);p.add_argument('--out',required=True);args=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];os.chdir(root)
platform=pathlib.Path(args.platform).resolve();tools=pathlib.Path(args.tools).resolve();ecj=pathlib.Path(args.ecj).resolve();keystore=pathlib.Path(args.keystore).resolve();pw=pathlib.Path(args.password_file).resolve();out=pathlib.Path(args.out).resolve()
build=root/'build';build.mkdir(exist_ok=True)
for name in ['classes','gen','dex']:
 if (build/name).exists():shutil.rmtree(build/name)
 (build/name).mkdir()
classes=build/'classes';gen=build/'gen';dex=build/'dex'
def run(cmd):subprocess.run([str(x) for x in cmd],check=True)
for name in ['aapt2','zipalign','apksigner','d8']:(tools/name).chmod(0o755)
run([tools/'aapt2','compile','--dir','app/src/main/res','-o',build/'resources.zip'])
run([tools/'aapt2','link','-I',platform/'android.jar','--manifest','app/src/main/AndroidManifest.xml','--java',gen,'--min-sdk-version','26','--target-sdk-version','35','-A','app/src/main/assets','-o',build/'resources.apk',build/'resources.zip'])
jars=sorted((root/'libs').glob('*.jar'));sources=sorted((root/'app/src/main/java').rglob('*.java'))+sorted(gen.rglob('*.java'))
run(['java','-jar',ecj,'-8','-encoding','UTF-8','-warn:none','-cp',os.pathsep.join(str(x) for x in [platform/'android.jar']+jars),'-d',classes,*sources])
with zipfile.ZipFile(build/'classes.jar','w',zipfile.ZIP_DEFLATED) as z:
 for f in classes.rglob('*.class'):z.write(f,f.relative_to(classes))
run([tools/'d8','--release','--min-api','26','--lib',platform/'android.jar','--output',dex,build/'classes.jar',*jars])
with zipfile.ZipFile(build/'resources.apk') as src,zipfile.ZipFile(build/'unsigned.apk','w',zipfile.ZIP_DEFLATED) as target:
 for item in src.infolist():target.writestr(item,src.read(item.filename))
 for f in dex.glob('*.dex'):target.write(f,f.name)
run([tools/'zipalign','-f','4',build/'unsigned.apk',build/'aligned.apk'])
if not keystore.exists():
 keystore.parent.mkdir(parents=True,exist_ok=True)
 run(['keytool','-genkeypair','-keystore',keystore,'-storepass:file',pw,'-keypass:file',pw,'-alias','modelforge','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=ModelForge Personal, O=Personal Android Project, C=JP','-storetype','PKCS12'])
out.parent.mkdir(parents=True,exist_ok=True)
run([tools/'apksigner','sign','--ks',keystore,'--ks-key-alias','modelforge','--ks-pass','file:'+str(pw),'--out',out,build/'aligned.apk'])
run([tools/'apksigner','verify','--verbose',out])
print('Built:',out,'bytes:',out.stat().st_size)
