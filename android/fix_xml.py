with open('F:/ibm hackathon/android/app/src/main/res/layout/activity_main.xml', 'r') as f:
    content = f.read()
new_content = content.replace('& Act', '& Act')
with open('F:/ibm hackathon/android/app/src/main/res/layout/activity_main.xml', 'w') as f:
    f.write(new_content)
print('Fixed')