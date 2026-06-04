# Internal Collaboration Guidelines
## API Guidelines
If you are unsure how to proceed, reference: https://yuque.antfin-inc.com/docs/share/8a5ff21a-6367-4c77-9e3c-1d5ae9570060?# yapi guide
## Internationalization Workflow
* Add new codes in `messages.properties`.
  * Naming convention: `scope.description`, for example `dataSource.sqlAnalysisError`.
* Option 1: Throw a business exception where user feedback is required.
```java
// The framework translates dataSource.sqlAnalysisError and returns the localized message to the frontend.
throw new BusinessException("dataSource.sqlAnalysisError");
```
* Option 2: Retrieve the localized copy directly without throwing an exception.
```java
// Fetch the translated text directly.
I18nUtils.getMessage("dataSource.sqlAnalysisError")
```
### Resolving Encoding Issues
Editor -> File Encodings -> Default encoding for properties files: switch to UTF-8.
### Editing i18n Files
Install the `Resource Bundle Editor` plugin. Click `messages.properties` and use the `Resource Bundle` panel at the bottom for convenient editing.
